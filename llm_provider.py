"""Provider-neutral LLM layer.

The Streamlit app supports two LLM back-ends behind one interface:

  * Google Gemini  (``GEMINI_API_KEY`` / ``GOOGLE_API_KEY``)
  * OpenAI GPT     (``OPENAI_API_KEY``)

Every operation in the app — the Word chat agent, the Markdown chat agent,
the PDF→Markdown conversion, and the checklist audit — talks to a
``Provider`` instance instead of a vendor SDK directly, so the user can pick
any model in the sidebar and have it drive all operations.

Two capabilities are exposed:

  * ``run_agent_loop`` — a function-calling (tool) loop.  History is kept in a
    provider-neutral list of dicts so a conversation can survive switching the
    selected model mid-session.
  * ``generate_json``  — single-shot generation returning a JSON string, with
    optional PDF input (multimodal) and an optional JSON schema hint.

Neutral history entries
-----------------------
    {"role": "user", "content": "<text>"}
    {"role": "assistant", "content": "<text>", "tool_calls": [{"id","name","args"}]}
    {"role": "tool_batch", "responses": [{"id","name","content"}]}
"""

from __future__ import annotations

import base64
import json
import os
from typing import Callable, Optional

# ---------------------------------------------------------------------------
# Model catalogue
# ---------------------------------------------------------------------------

GEMINI_MODELS = [
    "gemini-2.5-flash",
    "gemini-2.5-pro",
    "gemini-2.5-flash-lite",
    "gemini-2.0-flash",
    "gemini-flash-latest",
    "gemini-pro-latest",
]

OPENAI_MODELS = [
    "gpt-5",
    "gpt-5-mini",
    "gpt-4.1",
    "gpt-4.1-mini",
    "gpt-4o",
    "gpt-4o-mini",
]

ALL_MODELS = GEMINI_MODELS + OPENAI_MODELS


def provider_name_for_model(model: str) -> str:
    """Return "openai" or "gemini" for a given model name."""
    m = (model or "").lower()
    if m in (x.lower() for x in OPENAI_MODELS):
        return "openai"
    if m in (x.lower() for x in GEMINI_MODELS):
        return "gemini"
    # Heuristic fallback for models not in the static lists.
    if m.startswith("gpt") or m.startswith("o1") or m.startswith("o3") or m.startswith("o4"):
        return "openai"
    return "gemini"


def missing_key_message(model: str) -> str:
    if provider_name_for_model(model) == "openai":
        return "Set OPENAI_API_KEY in .env first."
    return "Set GEMINI_API_KEY in .env first."


# ---------------------------------------------------------------------------
# Tool-schema helpers
# ---------------------------------------------------------------------------

def _normalize_schema(tool: dict) -> dict:
    """Tools come in two flavours (`input_schema` or `parameters`)."""
    return (
        tool.get("input_schema")
        or tool.get("parameters")
        or {"type": "object", "properties": {}}
    )


def _strip_unsupported(schema):
    """Gemini's schema is a subset of JSONSchema — drop fields it rejects."""
    if isinstance(schema, dict):
        cleaned = {}
        for k, v in schema.items():
            if k in {"additionalProperties", "$schema", "title"}:
                continue
            cleaned[k] = _strip_unsupported(v)
        return cleaned
    if isinstance(schema, list):
        return [_strip_unsupported(v) for v in schema]
    return schema


# ---------------------------------------------------------------------------
# Base provider
# ---------------------------------------------------------------------------

class Provider:
    name = "base"

    def __init__(self, model: str):
        self.model = model

    # -- single-shot JSON generation (optionally multimodal) ----------------
    def generate_json(
        self,
        *,
        prompt: str,
        system: Optional[str] = None,
        pdf_bytes: Optional[bytes] = None,
        json_schema: Optional[dict] = None,
        temperature: Optional[float] = None,
        max_output_tokens: Optional[int] = None,
    ) -> str:
        raise NotImplementedError

    # -- tool / function-calling loop ---------------------------------------
    def _chat(self, system: Optional[str], tools: list[dict], history: list[dict]):
        """Return ``(text, tool_calls)`` for one model turn.

        ``tool_calls`` is a list of ``{"id", "name", "args"}`` dicts.
        """
        raise NotImplementedError

    def run_agent_loop(
        self,
        *,
        system: Optional[str],
        tools: list[dict],
        history: list[dict],
        user_text: str,
        dispatch: Callable[[str, dict], str],
        max_steps: int = 20,
    ) -> tuple[str, list[dict]]:
        """Drive the tool loop until the model stops requesting tools.

        ``history`` is mutated in place (so it persists in session state).
        ``dispatch(name, args)`` runs a tool and returns its string result.
        Returns ``(final_text, tool_log)``.
        """
        history.append({"role": "user", "content": user_text})

        final_text = ""
        tool_log: list[dict] = []

        for _ in range(max_steps):
            text, tool_calls = self._chat(system, tools, history)

            assistant_entry: dict = {"role": "assistant", "content": text}
            if tool_calls:
                assistant_entry["tool_calls"] = tool_calls
            history.append(assistant_entry)

            if text:
                final_text += text

            if not tool_calls:
                break

            responses = []
            for call in tool_calls:
                result = dispatch(call["name"], call["args"])
                tool_log.append(
                    {"name": call["name"], "args": call["args"], "result": result}
                )
                responses.append(
                    {"id": call["id"], "name": call["name"], "content": result}
                )
            history.append({"role": "tool_batch", "responses": responses})

        return final_text or "_(no reply)_", tool_log


# ---------------------------------------------------------------------------
# Gemini provider
# ---------------------------------------------------------------------------

class GeminiProvider(Provider):
    name = "gemini"

    def __init__(self, api_key: str, model: str):
        super().__init__(model)
        from google import genai  # lazy import — only when actually used

        self._genai = genai
        self.client = genai.Client(api_key=api_key)

    # --- conversions -------------------------------------------------------
    def _to_contents(self, history: list[dict]):
        from google.genai import types

        contents = []
        for m in history:
            role = m["role"]
            if role == "user":
                contents.append(
                    types.Content(role="user", parts=[types.Part(text=m["content"])])
                )
            elif role == "assistant":
                parts = []
                if m.get("content"):
                    parts.append(types.Part(text=m["content"]))
                for c in m.get("tool_calls", []):
                    parts.append(
                        types.Part(
                            function_call=types.FunctionCall(
                                name=c["name"], args=c.get("args") or {}
                            )
                        )
                    )
                if not parts:
                    parts.append(types.Part(text=""))
                contents.append(types.Content(role="model", parts=parts))
            elif role == "tool_batch":
                parts = [
                    types.Part.from_function_response(
                        name=r["name"], response={"result": r["content"]}
                    )
                    for r in m["responses"]
                ]
                contents.append(types.Content(role="user", parts=parts))
        return contents

    def _to_tool(self, tools: list[dict]):
        from google.genai import types

        declarations = []
        for t in tools:
            schema = _normalize_schema(t)
            if not schema.get("properties"):
                declarations.append(
                    types.FunctionDeclaration(
                        name=t["name"], description=t.get("description", "")
                    )
                )
            else:
                declarations.append(
                    types.FunctionDeclaration(
                        name=t["name"],
                        description=t.get("description", ""),
                        parameters=_strip_unsupported(schema),
                    )
                )
        return types.Tool(function_declarations=declarations)

    # --- API ---------------------------------------------------------------
    def _chat(self, system, tools, history):
        from google.genai import types

        config = types.GenerateContentConfig(
            system_instruction=system,
            tools=[self._to_tool(tools)],
        )
        response = self.client.models.generate_content(
            model=self.model,
            contents=self._to_contents(history),
            config=config,
        )
        candidate = response.candidates[0]
        parts = candidate.content.parts or []

        text = "".join(p.text for p in parts if getattr(p, "text", None))
        tool_calls = []
        for p in parts:
            fc = getattr(p, "function_call", None)
            if fc:
                tool_calls.append(
                    {
                        "id": fc.name,
                        "name": fc.name,
                        "args": dict(fc.args) if fc.args else {},
                    }
                )
        return text, tool_calls

    def generate_json(
        self,
        *,
        prompt,
        system=None,
        pdf_bytes=None,
        json_schema=None,
        temperature=None,
        max_output_tokens=None,
    ) -> str:
        from google.genai import types

        parts = []
        if pdf_bytes is not None:
            parts.append(
                types.Part.from_bytes(data=pdf_bytes, mime_type="application/pdf")
            )
        parts.append(prompt)

        config_kwargs: dict = {"response_mime_type": "application/json"}
        if system:
            config_kwargs["system_instruction"] = system
        if json_schema:
            config_kwargs["response_schema"] = json_schema
        if temperature is not None:
            config_kwargs["temperature"] = temperature
        if max_output_tokens is not None:
            config_kwargs["max_output_tokens"] = max_output_tokens

        response = self.client.models.generate_content(
            model=self.model,
            contents=parts,
            config=types.GenerateContentConfig(**config_kwargs),
        )
        return (response.text or "").strip()


# ---------------------------------------------------------------------------
# OpenAI provider
# ---------------------------------------------------------------------------

class OpenAIProvider(Provider):
    name = "openai"

    def __init__(self, api_key: str, model: str):
        super().__init__(model)
        from openai import OpenAI  # lazy import — only when actually used

        self.client = OpenAI(api_key=api_key)

    @property
    def _is_reasoning_model(self) -> bool:
        m = self.model.lower()
        return m.startswith("o1") or m.startswith("o3") or m.startswith("o4") or m.startswith("gpt-5")

    # --- conversions -------------------------------------------------------
    def _to_messages(self, system, history: list[dict]) -> list[dict]:
        msgs: list[dict] = []
        if system:
            msgs.append({"role": "system", "content": system})
        for m in history:
            role = m["role"]
            if role == "user":
                msgs.append({"role": "user", "content": m["content"]})
            elif role == "assistant":
                entry: dict = {"role": "assistant", "content": m.get("content") or None}
                if m.get("tool_calls"):
                    entry["tool_calls"] = [
                        {
                            "id": c["id"],
                            "type": "function",
                            "function": {
                                "name": c["name"],
                                "arguments": json.dumps(c.get("args") or {}),
                            },
                        }
                        for c in m["tool_calls"]
                    ]
                msgs.append(entry)
            elif role == "tool_batch":
                for r in m["responses"]:
                    msgs.append(
                        {
                            "role": "tool",
                            "tool_call_id": r["id"],
                            "content": r["content"],
                        }
                    )
        return msgs

    def _to_tools(self, tools: list[dict]) -> list[dict]:
        out = []
        for t in tools:
            out.append(
                {
                    "type": "function",
                    "function": {
                        "name": t["name"],
                        "description": t.get("description", ""),
                        "parameters": _normalize_schema(t),
                    },
                }
            )
        return out

    # --- API ---------------------------------------------------------------
    def _chat(self, system, tools, history):
        response = self.client.chat.completions.create(
            model=self.model,
            messages=self._to_messages(system, history),
            tools=self._to_tools(tools),
        )
        msg = response.choices[0].message
        text = msg.content or ""
        tool_calls = []
        for tc in msg.tool_calls or []:
            try:
                args = json.loads(tc.function.arguments or "{}")
            except json.JSONDecodeError:
                args = {}
            tool_calls.append({"id": tc.id, "name": tc.function.name, "args": args})
        return text, tool_calls

    def generate_json(
        self,
        *,
        prompt,
        system=None,
        pdf_bytes=None,
        json_schema=None,
        temperature=None,
        max_output_tokens=None,
    ) -> str:
        content: list[dict] = [{"type": "text", "text": prompt}]
        if pdf_bytes is not None:
            b64 = base64.b64encode(pdf_bytes).decode("ascii")
            content.append(
                {
                    "type": "file",
                    "file": {
                        "filename": "document.pdf",
                        "file_data": f"data:application/pdf;base64,{b64}",
                    },
                }
            )

        messages: list[dict] = []
        if system:
            messages.append({"role": "system", "content": system})
        messages.append({"role": "user", "content": content})

        kwargs: dict = {
            "model": self.model,
            "messages": messages,
            "response_format": {"type": "json_object"},
        }
        # Reasoning models reject custom temperature; only set it elsewhere.
        if temperature is not None and not self._is_reasoning_model:
            kwargs["temperature"] = temperature

        response = self.client.chat.completions.create(**kwargs)
        return (response.choices[0].message.content or "").strip()


# ---------------------------------------------------------------------------
# Factory
# ---------------------------------------------------------------------------

def get_provider(model: str) -> Optional[Provider]:
    """Build the right provider for ``model``, or ``None`` if its key is unset."""
    name = provider_name_for_model(model)
    if name == "openai":
        api_key = os.environ.get("OPENAI_API_KEY")
        if not api_key:
            return None
        return OpenAIProvider(api_key, model)

    api_key = os.environ.get("GEMINI_API_KEY") or os.environ.get("GOOGLE_API_KEY")
    if not api_key:
        return None
    return GeminiProvider(api_key, model)
