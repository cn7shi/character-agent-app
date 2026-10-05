MESSAGES = {
    "MODEL_AUTH_FAILED": "模型服务鉴权失败，请检查本机配置。",
    "MODEL_RATE_LIMITED": "模型服务暂时限流，请稍后再试。",
    "MODEL_UNAVAILABLE": "模型服务暂时不可用。",
    "MODEL_REQUEST_REJECTED": "模型服务不接受当前请求，请检查模型配置。",
    "MODEL_TIMEOUT": "回复超时。",
    "MODEL_STREAM_INTERRUPTED": "模型回复在正常结束前中断。",
    "MODEL_PROTOCOL_ERROR": "模型服务返回了不支持的响应格式。",
    "MODEL_OUTPUT_TRUNCATED": "回复已达到模型输出限制，内容可能不完整。",
    "MODEL_CONTENT_FILTERED": "模型服务未能返回这次回复。",
    "MODEL_TOOLS_UNSUPPORTED": "当前聊天适配尚未启用工具调用。",
    "MODEL_EMPTY_RESPONSE": "模型服务未返回可显示的文本。",
}


class ModelFailure(Exception):
    def __init__(self, code: str):
        self.code = code
        super().__init__(MESSAGES[code])
