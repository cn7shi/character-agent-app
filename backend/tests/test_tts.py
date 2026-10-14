from character_app.application.tts_service import (
    parse_dialogue_and_emotion,
    resolve_character_voice_config,
    TtsService,
)
import pytest


def test_parse_dialogue_extracts_chinese_brackets_action_and_spoken_text():
    raw = "（靠在迈巴赫车门边，修长手指夹着未燃的烟，语气低冷）这份企划案，你改了三次还是没有切中要害。"
    clean_text, emotion = parse_dialogue_and_emotion(raw)
    assert clean_text == "这份企划案，你改了三次还是没有切中要害。"
    assert "语气低冷" in emotion


def test_parse_dialogue_extracts_english_brackets_and_asterisks():
    raw = "(轻笑一声，眼神带着几分玩味) 小猫，今晚你逃不掉了。"
    clean_text, emotion = parse_dialogue_and_emotion(raw)
    assert clean_text == "小猫，今晚你逃不掉了。"
    assert "轻笑一声" in emotion


def test_parse_dialogue_handles_pure_dialogue_without_brackets():
    raw = "天道无情，唯剑永恒。"
    clean_text, emotion = parse_dialogue_and_emotion(raw)
    assert clean_text == "天道无情，唯剑永恒。"
    assert emotion is None


def test_parse_dialogue_handles_action_only_fallback():
    raw = "（默默转过身，一言不发）"
    clean_text, emotion = parse_dialogue_and_emotion(raw)
    assert clean_text == "默默转过身，一言不发"
    assert "默默转过身" in (emotion or "")


def test_resolve_character_voice_config_for_huosijue():
    config = resolve_character_voice_config("霍司爵", emotion="语气低冷")
    assert config.voice == "zh-CN-YunjianNeural"
    assert config.pitch.startswith("-")


def test_resolve_character_voice_config_for_xiao_han():
    config = resolve_character_voice_config("萧寒", emotion=None)
    assert config.voice == "zh-CN-YunyangNeural"


def test_resolve_character_voice_config_for_hilda():
    config = resolve_character_voice_config("希尔达", emotion="从容冷静")
    assert config.voice == "zh-CN-XiaoxiaoNeural"


def test_resolve_character_voice_config_for_jiang_ye():
    config = resolve_character_voice_config("江野", emotion="挑眉轻笑")
    assert config.voice == "zh-CN-YunxiNeural"
