import asyncio
from contextlib import aclosing
import hashlib
import json
import logging
import time

from character_app.domain.chat import ChatRepository, TextModel, TERMINAL

logger = logging.getLogger(__name__)
INTERRUPTED = {"code": "RUN_INTERRUPTED", "message": "服务已停止，任务未完成。"}


class ChatService:
    def __init__(self, repository: ChatRepository, model: TextModel, timeout: float = 30):
        self.repository = repository
        self.model = model
        self.timeout = timeout
        self.closing = False
        self.wake = asyncio.Event()
        self.workers: dict[str, tuple[asyncio.Task, asyncio.Event]] = {}

    async def start(self):
        # D10/D11: persisted queued work is dispatched independently of the HTTP response.
        # Recovery runs only while this process owns the data-directory lock.
        await asyncio.to_thread(self.repository.recover)
        self.dispatcher = asyncio.create_task(self._dispatch())

    async def close(self):
        self.closing = True
        self.wake.set()
        await self.dispatcher
        for _, stop in self.workers.values():
            stop.set()
        await asyncio.gather(*(task for task, _ in self.workers.values()))
        await asyncio.to_thread(self.repository.recover)

    async def create(self, user_id, conversation_id, client_message_id, text, key):
        payload = json.dumps([conversation_id, client_message_id, text], ensure_ascii=False)
        fingerprint = hashlib.sha256(payload.encode()).hexdigest()
        result = await asyncio.to_thread(self.repository.create_run, user_id, conversation_id,
                                        client_message_id, text, key, fingerprint,
                                        self.model.name, self.model.is_simulated)
        self.wake.set()
        return result

    async def cancel(self, user_id, run_id):
        result = await asyncio.to_thread(self.repository.cancel, user_id, run_id)
        worker = self.workers.get(run_id)
        if worker:
            worker[1].set()
        return result

    async def _dispatch(self):
        while not self.closing:
            self.wake.clear()
            for run_id, (task, _) in list(self.workers.items()):
                if task.done():
                    task.result()
                    del self.workers[run_id]
            try:
                queued = await asyncio.to_thread(self.repository.queued, 8)
                for user_id, run_id in queued:
                    if len(self.workers) >= 8 or self.closing:
                        break
                    if run_id not in self.workers:
                        stop = asyncio.Event()
                        self.workers[run_id] = (asyncio.create_task(self._execute(user_id, run_id, stop)), stop)
            except Exception:
                logger.exception("Could not dispatch queued runs")
            try:
                await asyncio.wait_for(self.wake.wait(), .1)
            except TimeoutError:
                pass

    async def _execute(self, user_id, run_id, stop):
        try:
            if not await asyncio.to_thread(self.repository.start, user_id, run_id):
                return
            context = await asyncio.to_thread(self.repository.context, user_id, run_id)
            deadline = time.monotonic() + self.timeout
            text = ""
            async with aclosing(self.model.stream(context)) as stream:
                while not stop.is_set():
                    next_chunk = asyncio.create_task(anext(stream))
                    stopped = asyncio.create_task(stop.wait())
                    try:
                        done, _ = await asyncio.wait({next_chunk, stopped}, timeout=max(0, deadline - time.monotonic()), return_when=asyncio.FIRST_COMPLETED)
                        if stopped in done:
                            break
                        if next_chunk not in done:
                            raise TimeoutError()
                        try:
                            chunk = next_chunk.result()
                        except StopAsyncIteration:
                            break
                    finally:
                        # Cancel only provider reads, never a database write running in a thread.
                        for task in (next_chunk, stopped):
                            if not task.done():
                                task.cancel()
                        await asyncio.gather(next_chunk, stopped, return_exceptions=True)
                    text += chunk
                    if len(text) > 16000:
                        raise ValueError("Model output exceeds local limit")
                    if not await asyncio.to_thread(self.repository.update_text, user_id, run_id, text):
                        stop.set()
            status = "failed" if self.closing else "cancelled" if stop.is_set() else "completed"
            await asyncio.to_thread(self.repository.finish, user_id, run_id, status, INTERRUPTED if self.closing else None)
        except Exception as exc:
            code = "MODEL_TIMEOUT" if isinstance(exc, TimeoutError) else "MODEL_ERROR"
            # Provider exceptions may contain credentials/prompts; store only a safe public error.
            error = {"code": code, "message": "回复超时。" if code == "MODEL_TIMEOUT" else "回复生成失败。"}
            try:
                await asyncio.to_thread(self.repository.finish, user_id, run_id, "failed", error)
            except Exception:
                logger.exception("Could not persist terminal run state")
        finally:
            self.wake.set()

    async def events(self, user_id, run_id, after):
        heartbeat = time.monotonic()
        while True:
            events, status, latest = await asyncio.to_thread(self.repository.events, user_id, run_id, after)
            for event in events:
                after = event["sequence"]
                yield f"id: {after}\nevent: {event['type']}\ndata: {json.dumps(event, ensure_ascii=False)}\n\n"
            if status in TERMINAL and after >= latest:
                return
            if time.monotonic() - heartbeat >= 15:
                yield ": heartbeat\n\n"
                heartbeat = time.monotonic()
            await asyncio.sleep(.1)
