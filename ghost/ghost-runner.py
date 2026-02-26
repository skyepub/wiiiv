#!/usr/bin/env python3
"""
GHOST Runner — Generated Human-like Orchestrated Stress Test
wiiiv 적대적 대화 시뮬레이터 (stdlib only, no pip)

Usage:
    python3 ghost-runner.py                     # 기본 시나리오 (GX-C01)
    python3 ghost-runner.py --case GX-C01       # 특정 케이스
    python3 ghost-runner.py --list              # 케이스 목록
    python3 ghost-runner.py --max-turns 10      # 턴 제한 오버라이드
"""

from __future__ import annotations

import argparse
import http.client
import json
import os
import ssl
import sys
import time
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Any

# ============================================================
# [1] Constants
# ============================================================

WIIIV_HOST = "localhost"
WIIIV_PORT = 8235
WIIIV_BASE = f"http://{WIIIV_HOST}:{WIIIV_PORT}"

OPENAI_API_KEY = os.environ.get("OPENAI_API_KEY", "")
OPENAI_MODEL = "gpt-4o"

MAX_SSE_WAIT = 300
DEFAULT_TIMEOUT = 120

SCRIPT_DIR = Path(__file__).resolve().parent
RESULTS_DIR = SCRIPT_DIR / "results"

# ANSI
class C:
    RESET   = "\033[0m"
    BOLD    = "\033[1m"
    RED     = "\033[91m"
    GREEN   = "\033[92m"
    YELLOW  = "\033[93m"
    BLUE    = "\033[94m"
    CYAN    = "\033[96m"
    MAGENTA = "\033[95m"
    DIM     = "\033[2m"


# ============================================================
# [2] Scenario Model
# ============================================================

@dataclass
class Scenario:
    case_id: str
    name: str
    persona: str
    goal: str
    constraints: list[str]
    max_turns: int = 20
    judge_criteria: str = ""
    first_message_hint: str = ""   # hint for the first message style


# ============================================================
# [3] Built-in Scenarios
# ============================================================

SCENARIOS: dict[str, Scenario] = {
    "GX-C01": Scenario(
        case_id="GX-C01",
        name="박대리의 급한 API 조회",
        persona=(
            "IT 부서 박대리. 30대 남성. 바쁘고 말이 짧다. "
            "오타가 가끔 있고, 존댓말과 반말을 섞어 쓴다. "
            "기술적 용어를 대충 알지만 정확하지 않다. "
            "처음에는 구체적인 정보를 주지 않고 대충 말한다."
        ),
        goal="skymall 쇼핑몰에서 상품 목록을 API로 조회하는 작업을 wiiiv에게 시키고, 실제 결과를 받는다",
        constraints=[
            "처음에는 모호하게 말할 것 ('상품 좀 가져와봐' 수준)",
            "wiiiv가 질문하면 조금씩 구체적 정보를 준다",
            "최소 1번은 '아 그거 말고' 식으로 정정한다",
            "API 주소를 처음에 안 줌 — 물어보면 'home.skyepub.net 9090 거기' 식으로 대충 답한다",
        ],
        max_turns=15,
        judge_criteria="wiiiv가 API 호출을 실행하거나 실행 계획(Blueprint/HLX)을 수립하면 성공",
        first_message_hint="상품 목록 좀 가져와봐. skymall꺼.",
    ),

    "GX-C02": Scenario(
        case_id="GX-C02",
        name="김팀장의 워크플로우 생성",
        persona=(
            "영업3팀 김팀장. 40대 남성. 말이 많고 장황하다. "
            "IT를 잘 모르지만 자기가 원하는 건 확실히 안다. "
            "비유를 많이 쓰고, 중간에 딴소리를 한다. "
            "'그러니까 내가 원하는 건...'을 반복한다."
        ),
        goal="매일 아침 9시에 skystock 재고 현황을 확인해서 부족한 품목을 알려주는 워크플로우를 만든다",
        constraints=[
            "처음에 장황하게 배경 설명을 함 (팀 상황, 왜 필요한지 등)",
            "기술 용어를 틀리게 사용 (API를 '에이피아이'라고 쓰는 식)",
            "중간에 한 번 '아 참 그리고' 식으로 추가 요구사항을 덧붙임",
            "wiiiv가 확인 요청하면 '그래 그래 그거야' 식으로 승인",
        ],
        max_turns=25,
        judge_criteria="워크플로우가 생성되거나 생성 직전 확인 단계까지 도달하면 성공",
        first_message_hint=(
            "김팀장인데요, 우리팀이 매일 아침마다 재고 확인을 수동으로 하거든요. "
            "이게 너무 번거로워서... 자동으로 좀 해줬으면 좋겠어요."
        ),
    ),
    "GX-C03": Scenario(
        case_id="GX-C03",
        name="이과장의 크로스시스템 조회",
        persona=(
            "경영지원팀 이과장. 30대 여성. 논리적이고 꼼꼼하다. "
            "두 시스템(skymall, skystock)을 모두 사용하며, 데이터를 비교하고 싶어한다. "
            "정확한 숫자와 결과를 원하고, 애매한 답변에는 다시 물어본다. "
            "존댓말을 쓰지만 단호한 편이다."
        ),
        goal=(
            "skymall에서 상품 목록을 조회한 뒤, "
            "skystock에서 해당 상품들의 재고 현황을 확인하여 두 시스템의 데이터를 비교한다"
        ),
        constraints=[
            "먼저 skymall(9090)에서 상품 목록을 요청한다",
            "결과를 받으면, 이어서 skystock(9091)에서 재고를 요청한다",
            "두 시스템을 명확히 구분하여 말한다 ('skymall 쪽', 'skystock 쪽' 등)",
            "중간에 한 번 '아까 skymall에서 받은 결과 다시 보여줘' 식으로 이전 결과 재요청",
            "최종적으로 두 시스템 데이터를 합쳐서 보고 싶다고 요청",
        ],
        max_turns=20,
        judge_criteria=(
            "skymall과 skystock 두 시스템 모두에 대해 API 호출이 실행되고 결과를 받으면 성공. "
            "한쪽 시스템만 조회했거나, 포트를 혼동한 경우 실패."
        ),
        first_message_hint=(
            "안녕하세요, 경영지원팀 이과장입니다. "
            "skymall에 등록된 상품 목록을 좀 조회해주실 수 있을까요?"
        ),
    ),
    "GX-C06": Scenario(
        case_id="GX-C06",
        name="최차장의 크로스시스템 자동화 워크플로우",
        persona=(
            "구매팀 최차장. 40대 남성. 업무 경험이 풍부하고 요구사항이 명확하다. "
            "IT 용어를 어느 정도 알지만 정확하지 않고, 비즈니스 관점에서 말한다. "
            "효율을 중시하며, '매번 수작업으로 하기 힘들다'는 말을 자주 한다. "
            "존댓말을 쓰되 직설적이다. 중간에 요구사항을 추가한다."
        ),
        goal=(
            "skymall에서 재고 부족 상품을 찾고, 해당 상품의 공급사를 skystock에서 조회한 뒤, "
            "결과를 /tmp/reorder_report.json에 저장하는 크로스시스템 워크플로우를 생성하고 실행한다"
        ),
        constraints=[
            "처음부터 '자동화'를 원한다고 명확히 말한다 — 워크플로우 생성을 유도",
            "skymall에서 재고 부족 상품을 먼저 뽑고, skystock에서 공급사를 찾는 2단계 흐름을 설명",
            "wiiiv가 인터뷰 질문을 하면 비즈니스 용어로 상세히 답한다",
            "중간에 '아 그리고 결과를 /tmp/reorder_report.json에 저장해주세요' 추가 요구",
            "WorkOrder 확인 요청이 오면 검토 후 승인한다",
            "최종 실행 결과를 확인하고 만족을 표현한다",
        ],
        max_turns=25,
        judge_criteria=(
            "워크플로우가 생성되어 실행되고, skymall과 skystock 두 시스템 모두 API 호출이 포함되면 성공. "
            "워크플로우 생성 없이 단순 API 호출만 했거나, 한쪽 시스템만 포함하면 실패."
        ),
        first_message_hint=(
            "안녕하세요, 구매팀 최차장입니다. "
            "매번 수작업으로 재고 부족 확인하고 공급사 찾는 게 너무 비효율적이에요. "
            "skymall에서 재고 부족한 상품을 뽑아서, 그 상품의 공급사를 skystock에서 자동으로 찾아주는 "
            "프로세스를 만들어줄 수 있나요?"
        ),
    ),
    "GX-C05": Scenario(
        case_id="GX-C05",
        name="정인턴의 잘못된 요청과 수정",
        persona=(
            "인사팀 정인턴. 20대 초반 여성. 입사 첫 주. "
            "IT 시스템을 전혀 모르고, 선배가 시킨 대로 하려는데 잘 안 된다. "
            "말을 더듬고, 틀린 정보를 주기도 한다. "
            "틀리면 '아 죄송합니다' 하고 바로 정정한다. "
            "반말은 절대 안 쓰고, 매우 공손하다."
        ),
        goal=(
            "skymall에서 특정 카테고리(Electronics)의 상품을 조회하고 "
            "결과를 /tmp/electronics.json에 저장한다"
        ),
        constraints=[
            "처음에 시스템 이름을 틀리게 말한다 ('스카이몰' 대신 '스카이마트' 또는 '스카이숍' 등)",
            "포트 번호를 잘못 알려준다 (9080이라고 하다가 나중에 9090으로 정정)",
            "중간에 '아 잠깐, 선배한테 다시 물어볼게요' 하고 잠시 뒤 정확한 정보 제공",
            "wiiiv가 에러를 알려주면 '죄송합니다, 제가 잘못 말씀드렸어요' 하고 정정",
            "최종적으로 올바른 정보로 Electronics 카테고리 상품 조회 + 파일 저장",
        ],
        max_turns=15,
        judge_criteria=(
            "skymall API 호출이 성공하고 Electronics 카테고리 관련 데이터가 조회되면 성공. "
            "사용자의 잘못된 정보로 인한 초기 실패는 허용하되, 최종적으로 성공해야 한다."
        ),
        first_message_hint=(
            "안녕하세요... 인사팀 정인턴입니다. "
            "선배가 스카이마트에서 전자제품 목록을 뽑아오라고 하셨는데, "
            "어떻게 하면 될까요?"
        ),
    ),
    "GX-C04": Scenario(
        case_id="GX-C04",
        name="한대리의 보고서 데이터 준비",
        persona=(
            "기획팀 한대리. 20대 후반 남성. 꼼꼼하지만 경험이 적다. "
            "상사에게 보고서를 준비해야 해서 급하다. "
            "처음에는 자신이 뭘 원하는지 정확히 모르고, 진행하면서 구체화한다. "
            "존댓말을 쓰고, '혹시'나 '그런데' 같은 표현을 자주 사용한다."
        ),
        goal=(
            "skymall에서 상품 목록을 조회하여 /tmp/report.json 파일로 저장하고, "
            "중간에 조건을 바꿔서 (예: 특정 카테고리만, 가격대 필터 등) 다시 저장을 요청한다"
        ),
        constraints=[
            "처음에는 '상품 데이터 좀 뽑아주세요' 수준으로 모호하게 요청",
            "wiiiv가 결과를 보여주면 '이걸 파일로 저장해주세요'라고 추가 요청",
            "파일 경로를 /tmp/report.json으로 지정",
            "저장 후 '아, 그런데 가격이 200달러 이하인 것만 다시 뽑아주세요' 식으로 조건 변경",
            "최종적으로 필터링된 결과도 파일로 저장 요청 (/tmp/report_filtered.json)",
        ],
        max_turns=15,
        judge_criteria=(
            "skymall API 호출이 실행되고 파일 저장(FILE_WRITE)이 최소 1회 수행되면 성공. "
            "API 호출만 하고 파일 저장이 없으면 실패."
        ),
        first_message_hint=(
            "안녕하세요, 기획팀 한대리입니다. "
            "혹시 skymall에 등록된 상품 데이터를 좀 뽑아주실 수 있을까요? "
            "보고서 준비하는 데 필요해서요."
        ),
    ),
}


# ============================================================
# [4] SSE Client (from hst-runner pattern)
# ============================================================

class SseClient:
    """Reads SSE from POST /api/v2/sessions/{sid}/chat."""

    def __init__(self, host: str, port: int, timeout: int = DEFAULT_TIMEOUT):
        self.host = host
        self.port = port
        self.timeout = timeout

    def chat(self, session_id: str, token: str, message: str) -> list[dict]:
        body = json.dumps({
            "message": message,
            "autoContinue": True,
            "maxContinue": 10,
        })
        conn = http.client.HTTPConnection(self.host, self.port, timeout=self.timeout)
        headers = {
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
            "Accept": "text/event-stream",
        }
        try:
            conn.request("POST", f"/api/v2/sessions/{session_id}/chat",
                         body=body, headers=headers)
            resp = conn.getresponse()
            if resp.status != 200:
                error_body = resp.read().decode("utf-8", errors="replace")
                raise RuntimeError(f"HTTP {resp.status}: {error_body[:500]}")
            return self._read_sse(resp)
        finally:
            conn.close()

    def _read_sse(self, resp: http.client.HTTPResponse) -> list[dict]:
        events = []
        buffer = ""
        cur_event = ""
        cur_data = ""
        start = time.time()

        while True:
            if time.time() - start > MAX_SSE_WAIT:
                break
            try:
                chunk = resp.read(4096)
            except Exception:
                break
            if not chunk:
                break

            buffer += chunk.decode("utf-8", errors="replace")
            while "\n" in buffer:
                line, buffer = buffer.split("\n", 1)
                line = line.rstrip("\r")
                if line.startswith("event:"):
                    cur_event = line[6:].strip()
                elif line.startswith("data:"):
                    cur_data = line[5:].strip()
                elif line == "":
                    if cur_event or cur_data:
                        parsed = {"event": cur_event, "raw_data": cur_data}
                        try:
                            parsed["data_parsed"] = json.loads(cur_data) if cur_data else {}
                        except (json.JSONDecodeError, ValueError):
                            parsed["data_parsed"] = {"text": cur_data}
                        events.append(parsed)
                        if cur_event in ("done", "error"):
                            return events
                    cur_event = ""
                    cur_data = ""
        return events


# ============================================================
# [5] Wiiiv Client (simplified)
# ============================================================

class WiiivClient:
    def __init__(self):
        self.token = ""
        self.sse = SseClient(WIIIV_HOST, WIIIV_PORT)

    def auto_login(self) -> str:
        resp = self._get("/api/v2/auth/auto-login")
        data = resp.get("data", resp)
        self.token = data.get("accessToken") or data.get("token", "")
        if not self.token:
            raise RuntimeError(f"auto-login failed: {resp}")
        return self.token

    def create_session(self, name: str = "ghost-test") -> str:
        resp = self._post("/api/v2/sessions", {"name": name})
        data = resp.get("data", resp)
        sid = data.get("sessionId", "")
        if not sid:
            raise RuntimeError(f"session creation failed: {resp}")
        return sid

    def chat(self, session_id: str, message: str) -> list[dict]:
        return self.sse.chat(session_id, self.token, message)

    def rag_size(self) -> int:
        """Check RAG store size."""
        resp = self._get("/api/v2/rag/size")
        data = resp.get("data", resp)
        return data.get("size", 0)

    def rag_ingest(self, file_path: str, title: str) -> bool:
        """Ingest a document into RAG store."""
        import pathlib
        content = pathlib.Path(file_path).read_text(encoding="utf-8")
        resp = self._post("/api/v2/rag/ingest", {
            "content": content,
            "title": title,
        })
        return resp.get("success", False)

    def _get(self, path: str) -> dict:
        conn = http.client.HTTPConnection(WIIIV_HOST, WIIIV_PORT, timeout=30)
        headers = {"Accept": "application/json"}
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        try:
            conn.request("GET", path, headers=headers)
            resp = conn.getresponse()
            body = resp.read().decode("utf-8", errors="replace")
            return json.loads(body) if body else {}
        finally:
            conn.close()

    def _post(self, path: str, data: dict) -> dict:
        conn = http.client.HTTPConnection(WIIIV_HOST, WIIIV_PORT, timeout=30)
        headers = {
            "Content-Type": "application/json",
            "Accept": "application/json",
        }
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        try:
            conn.request("POST", path, body=json.dumps(data), headers=headers)
            resp = conn.getresponse()
            body = resp.read().decode("utf-8", errors="replace")
            return json.loads(body) if body else {}
        finally:
            conn.close()


# ============================================================
# [6] Ghost LLM — generates human-like messages
# ============================================================

class GhostLlm:
    """Uses OpenAI API to generate persona messages."""

    def __init__(self, api_key: str, model: str = OPENAI_MODEL):
        self.api_key = api_key
        self.model = model

    def generate_first_message(self, scenario: Scenario) -> str:
        """Generate the ghost's opening message."""
        system = self._build_system_prompt(scenario)
        messages = [
            {"role": "system", "content": system},
            {"role": "user", "content": (
                "대화를 시작해. 너의 첫 번째 메시지를 보내.\n"
                f"힌트: {scenario.first_message_hint}"
            )},
        ]
        return self._call_openai(messages)

    def generate_next_message(self, scenario: Scenario,
                               conversation: list[dict],
                               turn_num: int) -> str:
        """Generate the ghost's next message based on conversation history."""
        system = self._build_system_prompt(scenario)
        messages = [{"role": "system", "content": system}]

        for entry in conversation:
            if entry["role"] == "ghost":
                messages.append({"role": "assistant", "content": entry["content"]})
            else:  # wiiiv
                messages.append({"role": "user", "content": entry["content"]})

        messages.append({"role": "user", "content": (
            f"[시스템: 현재 {turn_num}턴째. 최대 {scenario.max_turns}턴.]\n"
            "wiiiv의 위 응답을 보고, 네 페르소나에 맞게 다음 메시지를 보내.\n"
            "목표에 가까워지고 있으면 점점 구체적으로 답해.\n"
            "wiiiv가 확인을 요청하면 승인해도 좋다.\n"
            "목표를 이미 달성했으면 감사 인사 후 메시지 끝에 [DONE]을 붙여.\n"
            "메시지만 출력해. 설명이나 메타 코멘트 금지."
        )})
        return self._call_openai(messages)

    def judge_success(self, scenario: Scenario,
                       conversation: list[dict]) -> dict:
        """Judge whether the scenario goal was achieved."""
        conv_text = "\n".join(
            f"[{'GHOST' if e['role'] == 'ghost' else 'WIIIV'}] {e['content']}"
            for e in conversation
        )
        messages = [
            {"role": "system", "content": (
                "너는 테스트 판정관이다. 대화를 읽고 목표 달성 여부를 판단해.\n"
                "반드시 JSON으로만 답해: {\"pass\": true/false, \"reason\": \"판정 근거\"}"
            )},
            {"role": "user", "content": (
                f"## 시나리오: {scenario.name}\n"
                f"## 목표: {scenario.goal}\n"
                f"## 판정 기준: {scenario.judge_criteria}\n\n"
                f"## 대화 기록:\n{conv_text}\n\n"
                "판정해."
            )},
        ]
        raw = self._call_openai(messages)
        try:
            # Extract JSON from response
            if "```" in raw:
                raw = raw.split("```")[1]
                if raw.startswith("json"):
                    raw = raw[4:]
            return json.loads(raw.strip())
        except (json.JSONDecodeError, IndexError):
            return {"pass": False, "reason": f"판정 파싱 실패: {raw[:200]}"}

    def _build_system_prompt(self, scenario: Scenario) -> str:
        constraints_text = "\n".join(f"  - {c}" for c in scenario.constraints)
        return (
            "너는 GHOST(Generated Human-like Orchestrated Stress Test)의 가상 사용자다.\n"
            "wiiiv라는 AI 업무 자동화 시스템과 대화 중이다.\n"
            "너는 사람이고, wiiiv가 AI라는 것만 안다.\n"
            "너의 목표는 wiiiv를 실제 사용자처럼 자연스럽게 사용하는 것이다.\n\n"
            f"## 페르소나\n{scenario.persona}\n\n"
            f"## 목표\n{scenario.goal}\n\n"
            f"## 제약 (반드시 지킬 것)\n{constraints_text}\n\n"
            "## 규칙\n"
            "- 메시지만 출력한다. 설명, 괄호 코멘트, 메타 발언 금지.\n"
            "- 페르소나에 맞는 말투를 유지한다.\n"
            "- 한 번에 너무 많은 정보를 주지 않는다.\n"
            "- 실제 사람처럼 불완전하게 말한다.\n"
            "- wiiiv가 실행 확인을 요청하면, 자연스럽게 승인한다.\n"
            "- **목표를 달성했으면 인사 후 대화를 끝내라.** 마지막 메시지 끝에 [DONE] 태그를 붙여라.\n"
            "- 이미 목표를 달성한 뒤에 같은 내용을 반복하지 마라. 깔끔하게 끝내라.\n"
        )

    def _call_openai(self, messages: list[dict]) -> str:
        """Call OpenAI Chat Completion API (stdlib only)."""
        body = json.dumps({
            "model": self.model,
            "messages": messages,
            "temperature": 0.9,
            "max_tokens": 512,
        }).encode("utf-8")

        ctx = ssl.create_default_context()
        conn = http.client.HTTPSConnection("api.openai.com", context=ctx, timeout=60)
        headers = {
            "Authorization": f"Bearer {self.api_key}",
            "Content-Type": "application/json",
        }
        try:
            conn.request("POST", "/v1/chat/completions", body=body, headers=headers)
            resp = conn.getresponse()
            resp_body = resp.read().decode("utf-8")
            if resp.status != 200:
                raise RuntimeError(f"OpenAI API {resp.status}: {resp_body[:300]}")
            data = json.loads(resp_body)
            return data["choices"][0]["message"]["content"].strip()
        finally:
            conn.close()


# ============================================================
# [7] Response Parser
# ============================================================

def extract_wiiiv_response(events: list[dict]) -> tuple[str, str]:
    """Extract (action, message) from SSE events."""
    action = ""
    message_parts = []

    for ev in events:
        et = ev.get("event", "")
        dp = ev.get("data_parsed", {})

        if et == "action":
            action = dp.get("action", "")
        elif et == "response":
            text = dp.get("message", "") or dp.get("text", "")
            if text:
                message_parts.append(text)
            # confirmationSummary가 있으면 (WorkOrder 등) 메시지에 포함
            summary = dp.get("confirmationSummary", "")
            if summary and summary not in (text or ""):
                message_parts.append(summary)
        elif et == "progress":
            text = dp.get("message", "") or dp.get("text", "")
            if text:
                message_parts.append(f"[진행: {text}]")
        elif et == "error":
            text = dp.get("message", "") or dp.get("error", str(dp))
            message_parts.append(f"[ERROR: {text}]")

    # Deduplicate consecutive identical messages
    deduped = []
    for part in message_parts:
        if not deduped or deduped[-1] != part:
            deduped.append(part)

    return action, "\n".join(deduped)


# ============================================================
# [8] Ghost Runner
# ============================================================

class GhostRunner:
    def __init__(self):
        self.client = WiiivClient()
        self.llm = GhostLlm(OPENAI_API_KEY)
        self.conversation: list[dict] = []  # {role, content, turn, timestamp}

    def run(self, scenario: Scenario, max_turns_override: int | None = None) -> dict:
        max_turns = max_turns_override or scenario.max_turns
        start_time = time.time()

        print(f"\n{'='*60}")
        print(f"{C.MAGENTA}{C.BOLD}  GHOST  {C.RESET} {scenario.case_id}: {scenario.name}")
        print(f"{'='*60}")
        print(f"{C.DIM}  페르소나: {scenario.persona[:60]}...{C.RESET}")
        print(f"{C.DIM}  목표: {scenario.goal}{C.RESET}")
        print(f"{C.DIM}  최대 턴: {max_turns}{C.RESET}")
        print(f"{'='*60}\n")

        # --- Setup ---
        print(f"{C.DIM}[SETUP] auto-login...{C.RESET}")
        self.client.auto_login()

        # RAG ingest (in-memory RAG은 서버 재시작 시 초기화됨)
        rag_size = self.client.rag_size()
        if rag_size == 0:
            print(f"{C.DIM}[SETUP] RAG store empty — ingesting API specs...{C.RESET}")
            spec_dir = SCRIPT_DIR.parent / "test-wiiiv" / "phase3"
            specs = [
                (spec_dir / "skymall-api-spec-deployed.md", "skymall API spec"),
                (spec_dir / "skystock-api-spec-deployed.md", "skystock API spec"),
            ]
            for spec_path, title in specs:
                if spec_path.exists():
                    ok = self.client.rag_ingest(str(spec_path), title)
                    print(f"{C.DIM}[SETUP]   {title}: {'OK' if ok else 'FAIL'}{C.RESET}")
            rag_size = self.client.rag_size()
            print(f"{C.DIM}[SETUP] RAG store size: {rag_size}{C.RESET}")
        else:
            print(f"{C.DIM}[SETUP] RAG store: {rag_size} chunks{C.RESET}")

        print(f"{C.DIM}[SETUP] creating session...{C.RESET}")
        session_id = self.client.create_session(f"ghost-{scenario.case_id}")
        print(f"{C.DIM}[SETUP] session: {session_id}{C.RESET}\n")

        # --- Turn Loop ---
        for turn_num in range(1, max_turns + 1):
            print(f"{C.BOLD}--- Turn {turn_num}/{max_turns} ---{C.RESET}")

            # Generate ghost message
            if turn_num == 1:
                ghost_msg = self.llm.generate_first_message(scenario)
            else:
                ghost_msg = self.llm.generate_next_message(
                    scenario, self.conversation, turn_num
                )

            # [DONE] 태그 감지 → 조기 종료
            done_detected = "[DONE]" in ghost_msg
            if done_detected:
                ghost_msg = ghost_msg.replace("[DONE]", "").strip()

            self.conversation.append({
                "role": "ghost",
                "content": ghost_msg,
                "turn": turn_num,
                "timestamp": datetime.now().isoformat(),
            })
            print(f"{C.CYAN}[GHOST]{C.RESET} {ghost_msg}")

            if done_detected:
                print(f"{C.DIM}[GHOST] 목표 달성 — 대화 종료{C.RESET}\n")
                break

            # Send to wiiiv
            try:
                events = self.client.chat(session_id, ghost_msg)
            except Exception as e:
                print(f"{C.RED}[ERROR] wiiiv 통신 실패: {e}{C.RESET}")
                self.conversation.append({
                    "role": "wiiiv",
                    "content": f"[COMM ERROR: {e}]",
                    "turn": turn_num,
                    "timestamp": datetime.now().isoformat(),
                })
                continue

            action, wiiiv_msg = extract_wiiiv_response(events)
            self.conversation.append({
                "role": "wiiiv",
                "content": wiiiv_msg,
                "turn": turn_num,
                "action": action,
                "timestamp": datetime.now().isoformat(),
            })

            # Print wiiiv response
            action_tag = f" ({action})" if action else ""
            print(f"{C.GREEN}[WIIIV{action_tag}]{C.RESET} {wiiiv_msg[:500]}")
            if len(wiiiv_msg) > 500:
                print(f"{C.DIM}  ... ({len(wiiiv_msg)} chars total){C.RESET}")
            print()

            # 반복 패턴 감지 — 3턴 연속 HLX 실행 없이 짧은 메시지면 조기 종료
            if turn_num >= 3 and not action:
                recent_ghost = [
                    e["content"] for e in self.conversation[-6:]
                    if e["role"] == "ghost"
                ]
                if (len(recent_ghost) >= 3
                        and all(len(m) < 80 for m in recent_ghost[-3:])
                        and not any("=== HLX" in e.get("content", "")
                                    for e in self.conversation[-6:]
                                    if e.get("role") == "wiiiv")):
                    print(f"{C.DIM}[GHOST] 반복 패턴 감지 — 대화 조기 종료{C.RESET}\n")
                    break

            # Brief pause to be polite to the server
            time.sleep(1)

        # --- Judge ---
        elapsed = time.time() - start_time
        print(f"\n{'='*60}")
        print(f"{C.BOLD}  판정 중...{C.RESET}")

        verdict = self.llm.judge_success(scenario, self.conversation)
        passed = verdict.get("pass", False)
        reason = verdict.get("reason", "")

        if passed:
            print(f"{C.GREEN}{C.BOLD}  PASS{C.RESET} — {reason}")
        else:
            print(f"{C.RED}{C.BOLD}  FAIL{C.RESET} — {reason}")

        print(f"{C.DIM}  총 {len(self.conversation)} 메시지, {elapsed:.1f}초{C.RESET}")
        print(f"{'='*60}\n")

        # --- Save Result ---
        result = {
            "case_id": scenario.case_id,
            "name": scenario.name,
            "timestamp": datetime.now().isoformat(),
            "turns": len(self.conversation) // 2,
            "elapsed_sec": round(elapsed, 1),
            "verdict": "PASS" if passed else "FAIL",
            "reason": reason,
            "conversation": self.conversation,
        }
        self._save_result(result)
        return result

    def _save_result(self, result: dict):
        RESULTS_DIR.mkdir(parents=True, exist_ok=True)
        ts = datetime.now().strftime("%Y%m%d_%H%M%S")
        filename = f"{result['case_id']}_{ts}.json"
        path = RESULTS_DIR / filename
        with open(path, "w", encoding="utf-8") as f:
            json.dump(result, f, ensure_ascii=False, indent=2)
        print(f"{C.DIM}[SAVED] {path}{C.RESET}")


# ============================================================
# [9] Main
# ============================================================

def main():
    parser = argparse.ArgumentParser(description="GHOST Runner")
    parser.add_argument("--case", default="GX-C01", help="케이스 ID (기본: GX-C01)")
    parser.add_argument("--list", action="store_true", help="케이스 목록 출력")
    parser.add_argument("--max-turns", type=int, default=None, help="턴 수 오버라이드")
    args = parser.parse_args()

    if args.list:
        print(f"\n{C.BOLD}GHOST 케이스 목록:{C.RESET}")
        for cid, s in SCENARIOS.items():
            print(f"  {C.CYAN}{cid}{C.RESET} — {s.name} (최대 {s.max_turns}턴)")
            print(f"    {C.DIM}{s.goal}{C.RESET}")
        print()
        return

    if not OPENAI_API_KEY:
        print(f"{C.RED}ERROR: OPENAI_API_KEY 환경변수가 필요합니다{C.RESET}")
        sys.exit(1)

    case_id = args.case.upper()
    if case_id not in SCENARIOS:
        print(f"{C.RED}ERROR: 알 수 없는 케이스 '{case_id}'{C.RESET}")
        print(f"사용 가능: {', '.join(SCENARIOS.keys())}")
        sys.exit(1)

    scenario = SCENARIOS[case_id]
    runner = GhostRunner()
    runner.run(scenario, args.max_turns)


if __name__ == "__main__":
    main()
