"""Interactive chatbot powered by Claude Opus 4.7 (the same model behind Claude Code)."""

import os
import sys

from anthropic import Anthropic, APIError, APIConnectionError, RateLimitError
from dotenv import load_dotenv

load_dotenv()

MODEL = "claude-opus-4-7"

SYSTEM_PROMPT = (
    "You are a helpful, knowledgeable assistant. Answer questions clearly and "
    "concisely. When the user asks coding questions, provide working examples "
    "with brief explanations."
)


def chat() -> None:
    if not os.environ.get("ANTHROPIC_API_KEY"):
        print("ERROR: ANTHROPIC_API_KEY is not set.")
        print("Copy .env.example to .env and fill in your key, or export it in your shell.")
        sys.exit(1)

    client = Anthropic()
    history: list[dict] = []

    print(f"Chatbot ready (model: {MODEL}). Type 'exit' or 'quit' to leave.\n")

    while True:
        try:
            user_input = input("You: ").strip()
        except (EOFError, KeyboardInterrupt):
            print("\nGoodbye.")
            return

        if not user_input:
            continue
        if user_input.lower() in {"exit", "quit"}:
            print("Goodbye.")
            return
        if user_input.lower() == "reset":
            history = []
            print("[Conversation history cleared]\n")
            continue

        history.append({"role": "user", "content": user_input})

        try:
            print("Assistant: ", end="", flush=True)
            assistant_text = ""
            with client.messages.stream(
                model=MODEL,
                max_tokens=64000,
                system=SYSTEM_PROMPT,
                messages=history,
                thinking={"type": "adaptive"},
                output_config={"effort": "high"},
            ) as stream:
                for text in stream.text_stream:
                    print(text, end="", flush=True)
                    assistant_text += text
                final = stream.get_final_message()
            print("\n")

            history.append({"role": "assistant", "content": final.content})

        except RateLimitError:
            print("\n[Rate limited — wait a moment and try again]\n")
            history.pop()
        except APIConnectionError as e:
            print(f"\n[Connection error: {e}]\n")
            history.pop()
        except APIError as e:
            print(f"\n[API error: {e}]\n")
            history.pop()


if __name__ == "__main__":
    chat()
