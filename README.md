# docGenerator chatbot

A terminal chatbot powered by Claude Opus 4.7 — the same model behind Claude Code.

## Setup

```bash
cd C:/Users/chenz/Documents/docGenerator

# 1. Create a virtual environment
python -m venv .venv
.venv\Scripts\activate     # Windows
# source .venv/bin/activate  # macOS/Linux

# 2. Install dependencies
pip install -r requirements.txt

# 3. Configure your API key
copy .env.example .env     # Windows
# cp .env.example .env       # macOS/Linux
# then edit .env and paste your key from https://console.anthropic.com/
```

## Run

### General chatbot

```bash
python chatbot.py
```

### Word document agent

#### Web UI (recommended)

```bash
streamlit run app.py
```

Opens at http://localhost:8501. Pick or create a `.docx` in the sidebar, chat with the agent in the left panel, and edit the document on the right. The right panel has three tabs:

- **Edit (WYSIWYG)** — a Word-like rich-text editor (headings, bold/italic/underline, bullet/numbered lists, blockquotes). Click **💾 Save** to write changes back to the `.docx`.
- **Edit (structured)** — one row per paragraph with a text box and style dropdown. Useful for precise control or when WYSIWYG misbehaves.
- **Preview** — read-only Word-styled render.
- **Checklist** — audit the document against a set of checks (built-in default + custom). Two kinds of checks:
  - **Structural** rules run locally and instantly: title present, headings have body text, no leftover TODOs, minimum word count, etc.
  - **Content** rules ask the LLM to judge qualitative items ("intro states the purpose", "tone is consistent", "no obvious typos"). Click **▶️ Run checks** for both, or **⚡ Structural only** to skip the API call. Edit/add/remove items in the **Edit checklist** expander; the checklist is saved as a sidecar `<doc-name>.checklist.json`.

Manual edits and chatbot edits both write to the same `.docx`; whichever saves last wins. Tables, images, and nested lists are not yet supported in manual editing. Click **Download .docx** in the sidebar to save the file locally. Documents live in the `docs/` folder.

#### Command-line backends

```bash
# Anthropic Claude (paid, best quality)
python doc_editor.py path/to/my-doc.docx

# Google Gemini (free tier)
python doc_editor_gemini.py path/to/my-doc.docx

# Local Ollama (free, offline) - requires `ollama pull qwen2.5:7b` first
python doc_editor_ollama.py path/to/my-doc.docx
```

The agent has tools to read paragraphs, append/insert/replace/delete paragraphs, set styles (e.g. `Heading 1`, `List Bullet`), and find-and-replace. Each edit is saved to disk immediately. Example prompts:

- *"Add a title 'Quarterly Report' and a heading 'Summary' below it."*
- *"Replace every 'Q1' with 'Q2'."*
- *"Insert a bulleted list of three risks after paragraph 4."*
- *"Read the doc and tell me what's in paragraph 7."*

Commands inside either chat:
- `exit` / `quit` — leave the session
- `reset` — clear conversation history and start fresh
- `Ctrl+C` — exit at any time

## How it works

- **Model:** `claude-opus-4-7`
- **Adaptive thinking:** Claude decides on its own when and how deeply to think.
- **Streaming:** Tokens appear as they're generated.
- **Multi-turn memory:** Full conversation history is sent on each turn so the bot remembers context until you `reset`.
- **Tool use (doc agent):** The Word editor exposes `read_document`, `append_paragraph`, `insert_paragraph`, `set_paragraph`, `delete_paragraph`, and `replace_text` as Claude tools — same agent loop pattern as Claude Code.
