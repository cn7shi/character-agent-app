from contextlib import contextmanager
import logging
import traceback


@contextmanager
def redact_model_secret(secret: str):
    """Defense in depth for library logs; application errors never contain provider bodies."""
    if not secret:
        yield
        return
    previous = logging.getLogRecordFactory()

    def factory(*args, **kwargs):
        record = previous(*args, **kwargs)
        record.msg = record.getMessage().replace(secret, "[REDACTED]")
        record.args = ()
        if record.exc_info:
            record.exc_text = "".join(traceback.format_exception(*record.exc_info)).replace(secret, "[REDACTED]")
            record.exc_info = None
        if record.stack_info:
            record.stack_info = record.stack_info.replace(secret, "[REDACTED]")
        return record

    logging.setLogRecordFactory(factory)
    try:
        yield
    finally:
        logging.setLogRecordFactory(previous)
