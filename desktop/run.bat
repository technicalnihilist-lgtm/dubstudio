@echo off
cd /d "%~dp0"
if not exist .venv (
  python -m venv .venv
  .venv\Scripts\pip install -q -r requirements.txt
)
if not exist .env copy .env.example .env
.venv\Scripts\python app.py
