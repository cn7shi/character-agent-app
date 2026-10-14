import hashlib
import io
import re
from dataclasses import dataclass
from typing import Optional

import edge_tts


@dataclass(frozen=True)
class VoiceConfig:
    voice: str
    rate: str = "+0%"
    pitch: str = "+0Hz"
    volume: str = "+0%"


@dataclass(frozen=True)
class TtsAudioResult:
    audio_bytes: bytes
    mime_type: str
    duration_seconds: float
    clean_text: str
    emotion: Optional[str]
    voice: str


def parse_dialogue_and_emotion(raw_text: str) -> tuple[str, Optional[str]]:
    """
    Extracts action/emotion descriptions inside brackets and returns:
    (clean_dialogue_text, extracted_emotion_or_action).
    Prevents the character from literally reading bracketed stage directions.
    """
    if not raw_text or not raw_text.strip():
        return "", None

    text = raw_text.strip()
    
    # Match stage directions inside （...）, (...), 【...】, [...], or *...*
    pattern = r"[\(（【\[\*]([^\)）】\]\*]+)[\)）】\]\*]"
    matches = re.findall(pattern, text)
    
    extracted_emotion = "，".join(m.strip() for m in matches if m.strip()) if matches else None
    
    # Strip brackets from spoken dialogue
    clean_dialogue = re.sub(pattern, "", text).strip()
    
    # Strip surrounding quotes if present
    for quote_pair in [("“", "”"), ("\"", "\""), ("'", "'"), ("「", "」")]:
        if clean_dialogue.startswith(quote_pair[0]) and clean_dialogue.endswith(quote_pair[1]):
            clean_dialogue = clean_dialogue[len(quote_pair[0]):-len(quote_pair[1])].strip()
    
    # If the text was entirely an action description without spoken lines, fallback to speaking the action
    if not clean_dialogue and extracted_emotion:
        clean_dialogue = extracted_emotion
    
    # Remove redundant whitespace
    clean_dialogue = re.sub(r"\s+", " ", clean_dialogue).strip()
    return clean_dialogue, extracted_emotion


def resolve_character_voice_config(
    character_identifier: Optional[str] = None,
    emotion: Optional[str] = None
) -> VoiceConfig:
    """
    Selects one of the high-fidelity base neural voices and applies prosody tuning
    (pitch, rate) according to the character's persona and context emotion.
    """
    ident = (character_identifier or "").lower().strip()
    emo = (emotion or "").lower().strip()

    # Base Voice Selection
    if any(k in ident for k in ["霍司爵", "严烈", "霸总", "刑警", "总裁", "冷酷"]):
        base_voice = "zh-CN-YunjianNeural"  # Deep, magnetic, mature male
        base_rate = "-4%"
        base_pitch = "-6Hz"
    elif any(k in ident for k in ["萧寒", "剑仙", "玄幻", "高冷", "师尊"]):
        base_voice = "zh-CN-YunyangNeural"  # Clear, dignified, calm male
        base_rate = "-5%"
        base_pitch = "+0Hz"
    elif any(k in ident for k in ["希尔达", "女", "少女", "女武神", "公主"]):
        base_voice = "zh-CN-XiaoxiaoNeural"  # Expressive, versatile female
        base_rate = "+2%"
        base_pitch = "+0Hz"
    elif any(k in ident for k in ["沈言", "医生", "病娇", "温柔", "斯文"]):
        base_voice = "zh-CN-YunxiNeural"  # Youthful, magnetic male
        base_rate = "-3%"
        base_pitch = "-2Hz"
    elif any(k in ident for k in ["江野", "赛车手", "痞帅", "狼系", "少年"]):
        base_voice = "zh-CN-YunxiNeural"  # Vibrant, lively young male
        base_rate = "+8%"
        base_pitch = "+4Hz"
    else:
        # Default warm versatile young male
        base_voice = "zh-CN-YunxiNeural"
        base_rate = "+0%"
        base_pitch = "+0Hz"

    # Emotion Prosody Tuning
    rate_adjust = 0
    pitch_adjust = 0

    if any(k in emo for k in ["冷", "低沉", "压抑", "怒", "严肃", "命令"]):
        rate_adjust -= 3
        pitch_adjust -= 4
    elif any(k in emo for k in ["轻笑", "挑眉", "玩味", "戏谑", "欢快", "元气"]):
        rate_adjust += 4
        pitch_adjust += 4
    elif any(k in emo for k in ["耳语", "贴近", "低语", "温柔", "病娇"]):
        rate_adjust -= 4
        pitch_adjust -= 2
    elif any(k in emo for k in ["急", "喊", "惊", "慌"]):
        rate_adjust += 8
        pitch_adjust += 6

    # Format adjusted rate and pitch
    base_rate_val = int(base_rate.replace("%", "").replace("+", ""))
    final_rate_val = base_rate_val + rate_adjust
    rate_str = f"{final_rate_val:+d}%"

    base_pitch_val = int(base_pitch.replace("Hz", "").replace("+", ""))
    final_pitch_val = base_pitch_val + pitch_adjust
    pitch_str = f"{final_pitch_val:+d}Hz"

    return VoiceConfig(
        voice=base_voice,
        rate=rate_str,
        pitch=pitch_str,
        volume="+0%",
    )


class TtsService:
    """
    Deep module interface for Text-to-Speech synthesis.
    Encapsulates emotion parsing, voice resolution, SSML-like prosody tuning,
    Edge-TTS async communication, and memory caching.
    """

    def __init__(self) -> None:
        self._cache: dict[str, TtsAudioResult] = {}

    def _cache_key(self, voice: str, rate: str, pitch: str, text: str) -> str:
        content = f"{voice}:{rate}:{pitch}:{text}"
        return hashlib.sha256(content.encode("utf-8")).hexdigest()

    async def synthesize(
        self,
        raw_text: str,
        character_identifier: Optional[str] = None
    ) -> TtsAudioResult:
        clean_text, emotion = parse_dialogue_and_emotion(raw_text)
        if not clean_text:
            clean_text = "..."

        voice_cfg = resolve_character_voice_config(character_identifier, emotion)
        key = self._cache_key(voice_cfg.voice, voice_cfg.rate, voice_cfg.pitch, clean_text)

        if key in self._cache:
            return self._cache[key]

        communicate = edge_tts.Communicate(
            text=clean_text,
            voice=voice_cfg.voice,
            rate=voice_cfg.rate,
            pitch=voice_cfg.pitch,
            volume=voice_cfg.volume,
        )

        audio_buffer = io.BytesIO()
        async for chunk in communicate.stream():
            if chunk["type"] == "audio":
                audio_buffer.write(chunk["data"])

        audio_bytes = audio_buffer.getvalue()
        
        # Estimate duration roughly based on character count and rate (avg 3.5 chars/sec in Chinese)
        char_count = max(1, len(clean_text))
        base_dur = char_count / 3.5
        rate_factor = 1.0 + (int(voice_cfg.rate.replace("%", "")) / 100.0)
        estimated_duration = max(1.5, round(base_dur / max(0.5, rate_factor), 1))

        result = TtsAudioResult(
            audio_bytes=audio_bytes,
            mime_type="audio/mpeg",
            duration_seconds=estimated_duration,
            clean_text=clean_text,
            emotion=emotion,
            voice=voice_cfg.voice,
        )

        # Cache result
        self._cache[key] = result
        return result
