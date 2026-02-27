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
    "GX-C07": Scenario(
        case_id="GX-C07",
        name="윤부장의 연속 작업 요청",
        persona=(
            "경영기획실 윤부장. 50대 남성. 임원 보고를 준비 중이다. "
            "급하고, 한 작업이 끝나면 바로 다음 작업을 지시한다. "
            "이전 결과를 참조하며 '아까 그 데이터에서' 식으로 말한다. "
            "숫자에 민감하고, 정확한 데이터를 요구한다. "
            "존댓말이지만 명령조가 섞여 있다."
        ),
        goal=(
            "3단계 연속 작업: "
            "(1) skymall 전체 상품 조회 → "
            "(2) 아까 결과에서 가격 200달러 이상인 것만 다시 조회 → "
            "(3) 해당 상품의 skystock 공급사 조회. "
            "각 단계에서 이전 결과를 참조하며, 최종 결과를 /tmp/executive_report.json에 저장"
        ),
        constraints=[
            "1단계: skymall 전체 상품 목록을 요청한다",
            "2단계: 결과를 보고 '200달러 이상인 것만 다시 뽑아주세요' 식으로 필터 요청",
            "3단계: '아까 그 비싼 상품들의 공급사를 skystock에서 찾아주세요' 식으로 크로스시스템 요청",
            "최종적으로 '/tmp/executive_report.json에 저장해주세요' 요청",
            "wiiiv가 확인을 요청하면 빠르게 승인한다",
            "중간에 한 번 '아까 1단계 결과 다시 보여줘요' 식으로 이전 결과 재요청",
        ],
        max_turns=20,
        judge_criteria=(
            "skymall API 호출과 skystock API 호출이 모두 실행되고, "
            "파일 저장이 수행되면 성공. 3단계 중 하나라도 누락되면 실패."
        ),
        first_message_hint=(
            "윤부장입니다. 임원 보고 자료 준비해야 하니까 빨리 좀 도와주세요. "
            "먼저 skymall에 등록된 전체 상품 목록부터 뽑아주세요."
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
    "GX-C08": Scenario(
        case_id="GX-C08",
        name="송과장의 마음 바꾸기",
        persona=(
            "물류팀 송과장. 30대 후반 여성. 일을 잘 하지만 우선순위가 수시로 바뀐다. "
            "상사의 지시가 자주 바뀌어서, 하던 작업을 중단하고 다른 걸 하기도 한다. "
            "'아 잠깐만요' '아니 그거 말고' '죄송한데 급한 게 생겼어요' 를 자주 쓴다. "
            "존댓말을 쓰고, 미안해하면서 요청을 바꾼다."
        ),
        goal=(
            "최종적으로 skystock에서 공급사 목록을 조회하고 /tmp/suppliers.json에 저장한다. "
            "하지만 처음에는 skymall 상품 조회를 요청했다가 중간에 마음을 바꿔 skystock으로 전환한다."
        ),
        constraints=[
            "1단계: skymall 상품 조회를 요청한다",
            "2단계: wiiiv가 인터뷰/확인을 진행하는 도중에 '아 잠깐만요, 그거 말고요' 하고 중단한다",
            "3단계: '급한 게 생겼는데, skystock에서 공급사 목록을 먼저 봐야 해요. 결과는 /tmp/suppliers.json에 저장해주세요' 식으로 전환 — 파일 경로를 반드시 같은 메시지에 포함",
            "⚠ /tmp/suppliers.json 경로를 반드시 대화 중에 직접 언급해야 함. wiiiv가 자동으로 다른 이름을 쓸 수 있으므로.",
            "wiiiv가 확인을 요청하면 바로 승인한다",
        ],
        max_turns=15,
        judge_criteria=(
            "skystock API 호출이 실행되어 공급사 데이터를 받고, "
            "/tmp/suppliers.json 파일 저장이 수행되면 성공. "
            "skymall 작업을 계속 진행하거나, skystock 전환에 실패하면 실패."
        ),
        first_message_hint=(
            "안녕하세요, 물류팀 송과장입니다. "
            "skymall에 있는 상품 목록을 좀 조회해주실 수 있나요?"
        ),
    ),
    "GX-C09": Scenario(
        case_id="GX-C09",
        name="강사원의 파일 읽기와 후속 작업",
        persona=(
            "총무팀 강사원. 20대 중반 남성. 입사 2년차. "
            "엑셀 대신 JSON 파일을 다루라는 팀장 지시를 받았다. "
            "파일 경로를 정확히 알고 있고, 단계적으로 요청한다. "
            "말투가 깔끔하고 존댓말을 쓴다. 중간에 한 번 확인 질문을 한다."
        ),
        goal=(
            "/tmp/skymall_products.json 파일을 읽어서 내용을 확인한 뒤, "
            "해당 데이터에서 가격이 가장 높은 상품 정보를 /tmp/top_product.json에 저장한다"
        ),
        constraints=[
            "1단계: '/tmp/skymall_products.json 파일 내용 좀 읽어주세요' 식으로 파일 읽기 요청",
            "2단계: 결과를 확인한 뒤 '가장 비싼 상품이 뭔지' 질문",
            "3단계: '그 상품 정보를 /tmp/top_product.json에 저장해주세요' 요청",
            "wiiiv가 확인을 요청하면 바로 승인한다",
        ],
        max_turns=15,
        judge_criteria=(
            "파일 읽기(FILE_READ)가 실행되어 내용이 표시되고, "
            "최종적으로 /tmp/top_product.json에 파일 저장이 수행되면 성공. "
            "파일을 읽지 않거나, 저장이 수행되지 않으면 실패."
        ),
        first_message_hint=(
            "안녕하세요, 총무팀 강사원입니다. "
            "/tmp/skymall_products.json 파일 내용을 좀 확인해주실 수 있나요?"
        ),
    ),
    "GX-C10": Scenario(
        case_id="GX-C10",
        name="나대리의 장황한 멀티 요청",
        persona=(
            "마케팅팀 나대리. 20대 후반 남성. 말이 많고 한 번에 여러 가지를 요청한다. "
            "배경 설명이 길고, 요청 사항이 문장 중간에 묻혀 있다. "
            "IT 용어를 잘 모르고 비유를 많이 쓴다. "
            "'그니까요' '있잖아요' 를 자주 쓴다. 존댓말 사용."
        ),
        goal=(
            "skymall에서 상품 목록을 조회하고, "
            "Electronics 카테고리의 상품만 정리해서 /tmp/electronics_report.json에 저장한다"
        ),
        constraints=[
            "첫 메시지에 배경 설명(팀 상황, 왜 필요한지)을 장황하게 포함한다",
            "요청을 한 문장에 깔끔하게 하지 않고, 여러 문장에 걸쳐 흩어놓는다",
            "중간에 '아 그리고 Electronics 카테고리만요' 식으로 조건을 뒤늦게 추가한다",
            "파일 저장 경로를 /tmp/electronics_report.json으로 명시적으로 요청한다",
            "wiiiv가 확인을 요청하면 승인한다",
        ],
        max_turns=15,
        judge_criteria=(
            "skymall API 호출이 실행되고, Electronics 관련 데이터가 조회되며, "
            "/tmp/electronics_report.json에 파일 저장이 수행되면 성공."
        ),
        first_message_hint=(
            "안녕하세요, 마케팅팀 나대리입니다. 있잖아요, 요즘 팀에서 프로모션 기획을 하고 있는데요, "
            "그래서 말인데 skymall에 있는 상품들을 좀 봐야 하거든요. "
            "전체 목록을 뽑아주시면 좋겠는데, 아 맞다 특히 전자제품 쪽이 급해요."
        ),
    ),
    "GX-C11": Scenario(
        case_id="GX-C11",
        name="오팀장의 속사포 명령",
        persona=(
            "운영팀 오팀장. 40대 남성. 극도로 바쁘고 말이 짧다. "
            "한 단어~한 줄로 말한다. 배경 설명 없이 바로 명령한다. "
            "결과를 확인하면 바로 다음 명령. 감사 인사도 거의 없다. "
            "반말과 명령조를 섞어 쓴다. '빨리' '바로' 를 자주 쓴다."
        ),
        goal=(
            "4단계 속사포 작업: "
            "(1) skymall 상품 조회 → "
            "(2) 결과를 /tmp/ops_data.json에 저장 → "
            "(3) 방금 저장한 파일 읽어서 확인 → "
            "(4) skystock 공급사 목록 조회"
        ),
        constraints=[
            "모든 메시지를 1~2문장으로 짧게 한다",
            "1단계: 'skymall 상품 전체 뽑아' 수준으로 짧게 요청",
            "2단계: 결과 보고 바로 '/tmp/ops_data.json에 저장해' 명령",
            "3단계: 바로 '/tmp/ops_data.json 읽어봐' 명령",
            "4단계: 바로 'skystock 공급사 목록도 뽑아' 명령",
            "wiiiv가 질문하면 최소한의 답만 한다",
            "wiiiv가 확인을 요청하면 '응' 또는 '그래' 로 짧게 승인",
        ],
        max_turns=15,
        judge_criteria=(
            "4단계 모두 실행되어야 성공: "
            "(1) skymall API 호출 성공, (2) /tmp/ops_data.json 파일 저장, "
            "(3) 파일 읽기 실행, (4) skystock API 호출 성공. "
            "하나라도 누락되면 실패."
        ),
        first_message_hint="skymall 상품 전체 뽑아. 빨리.",
    ),
    "GX-C12": Scenario(
        case_id="GX-C12",
        name="권차장의 모호한 시스템 참조",
        persona=(
            "구매팀 권차장. 40대 남성. 시스템 이름을 정확히 기억하지 못한다. "
            "'그 쇼핑몰 시스템' '재고 관리하는 거' 식으로 모호하게 말한다. "
            "wiiiv가 물어보면 '아 그거 뭐더라... 포트가 9천번대였는데' 식으로 힌트를 준다. "
            "존댓말이지만 투덜거린다."
        ),
        goal="skymall에서 상품 목록을 조회한다",
        constraints=[
            "시스템 이름을 절대 'skymall'이라고 직접 말하지 않는다",
            "'쇼핑몰 시스템', '상품 파는 데', '그 9090 포트 거기' 등으로 돌려 말한다",
            "wiiiv가 'skymall 말씀이신가요?' 하면 '아 맞아 그거' 식으로 확인",
            "최종적으로 상품 목록 결과를 받으면 만족",
        ],
        max_turns=15,
        judge_criteria="skymall API 호출이 실행되어 상품 데이터를 받으면 성공",
        first_message_hint="저기요, 그 쇼핑몰 시스템 있잖아요. 거기서 상품 목록 좀 뽑아주세요.",
    ),
    "GX-C13": Scenario(
        case_id="GX-C13",
        name="임인턴의 대화 중 실행 전환",
        persona=(
            "개발팀 임인턴. 20대 초반 여성. 호기심이 많고 질문이 많다. "
            "처음에는 시스템에 대해 이것저것 물어보다가, 갑자기 실행을 요청한다. "
            "'그럼 한번 해볼까요?' 식으로 자연스럽게 전환한다. "
            "매우 공손하고, 이모티콘을 쓰고 싶어하지만 참는다."
        ),
        goal=(
            "처음에 wiiiv에게 skymall 시스템에 대해 질문(대화)을 하다가, "
            "자연스럽게 실제 API 호출로 전환하여 상품 목록을 조회한다"
        ),
        constraints=[
            "1단계: 'skymall이 어떤 시스템인가요?' 같은 정보성 질문으로 시작",
            "2단계: 'API로 뭘 할 수 있나요?' 같은 추가 질문",
            "3단계: '그럼 상품 목록을 한번 조회해볼 수 있을까요?' 식으로 실행 전환",
            "wiiiv가 확인을 요청하면 승인한다",
        ],
        max_turns=15,
        judge_criteria=(
            "초반에 대화(CONVERSATION/INFORMATION) 응답이 있고, "
            "이후 skymall API 호출이 실행되어 상품 데이터를 받으면 성공"
        ),
        first_message_hint="안녕하세요! skymall이라는 시스템이 있다고 들었는데, 어떤 시스템인가요?",
    ),
    "GX-C14": Scenario(
        case_id="GX-C14",
        name="조부장의 데이터 비교 요청",
        persona=(
            "재무팀 조부장. 50대 여성. 숫자에 매우 민감하고, 정확한 비교를 원한다. "
            "두 시스템의 데이터를 나란히 보고 싶어한다. "
            "'정확히 몇 개인지', '차이가 얼마인지' 구체적으로 묻는다. "
            "존댓말을 쓰고, 비즈니스 용어를 사용한다."
        ),
        goal=(
            "skymall에서 상품 수와 skystock에서 공급사 수를 각각 조회하여 비교한다. "
            "결과를 /tmp/comparison.json에 저장한다"
        ),
        constraints=[
            "1단계: skymall 상품이 총 몇 개인지 조회 요청",
            "2단계: skystock 공급사가 총 몇 개인지 조회 요청",
            "3단계: '두 결과를 /tmp/comparison.json에 정리해서 저장해주세요' 요청",
            "wiiiv가 확인을 요청하면 승인한다",
        ],
        max_turns=15,
        judge_criteria=(
            "skymall과 skystock 두 시스템 모두 API 호출이 실행되고, "
            "/tmp/comparison.json 파일 저장이 수행되면 성공"
        ),
        first_message_hint=(
            "안녕하세요, 재무팀 조부장입니다. "
            "skymall에 등록된 상품이 현재 총 몇 개인지 확인해주실 수 있나요?"
        ),
    ),
    "GX-C15": Scenario(
        case_id="GX-C15",
        name="유사원의 같은 요청 반복",
        persona=(
            "총무팀 유사원. 20대 중반 여성. 처음 결과를 보고 놀라서 '진짜요?' 하고 다시 요청한다. "
            "같은 작업을 한 번 더 해달라고 한다. 숫자가 맞는지 의심한다. "
            "'혹시 아까 결과가 맞나요?' '한 번 더 확인해볼 수 있어요?' "
            "존댓말을 쓰고, 조심스러운 편이다."
        ),
        goal=(
            "skymall 상품 조회를 2회 실행한다. "
            "첫 번째 결과를 보고 '다시 한 번 확인해주세요'라고 재요청한다"
        ),
        constraints=[
            "1단계: skymall 상품 목록 조회 요청",
            "2단계: 결과를 보고 '진짜 이게 전부인가요? 한 번 더 확인해주세요' 식으로 재요청",
            "3단계: 두 번째 결과를 받으면 만족하고 종료",
            "wiiiv가 확인을 요청하면 승인한다",
        ],
        max_turns=15,
        judge_criteria=(
            "skymall API 호출이 최소 2회 실행되면 성공. "
            "1회만 실행하고 '아까 결과와 같습니다'로 대체하면 실패."
        ),
        first_message_hint=(
            "안녕하세요, 총무팀 유사원입니다. "
            "skymall에 상품이 몇 개나 있는지 목록을 좀 조회해주실 수 있나요?"
        ),
    ),
    "GX-C16": Scenario(
        case_id="GX-C16",
        name="배과장의 구체적 기술 요청",
        persona=(
            "IT팀 배과장. 30대 남성. API를 정확히 알고 있다. "
            "엔드포인트, HTTP 메서드, 포트 번호를 직접 지정한다. "
            "기술적으로 정확하게 말하며, 모호한 부분이 없다. "
            "반말을 쓰고, 간결하다."
        ),
        goal=(
            "skymall의 GET /api/products 엔드포인트를 직접 지정하여 호출하고, "
            "결과를 /tmp/products_raw.json에 저장한다"
        ),
        constraints=[
            "API 호출 정보를 직접 제공: host=home.skyepub.net, port=9090, path=/api/products, method=GET",
            "인증 정보도 알려줌: POST /api/auth/login, username=jane_smith, password=password123",
            "결과를 /tmp/products_raw.json에 저장 요청",
            "wiiiv가 확인을 요청하면 바로 승인",
        ],
        max_turns=10,
        judge_criteria=(
            "skymall API 호출이 실행되어 상품 데이터를 받고, "
            "/tmp/products_raw.json에 파일 저장이 수행되면 성공"
        ),
        first_message_hint=(
            "home.skyepub.net:9090에 GET /api/products 호출해줘. "
            "인증은 POST /api/auth/login에 jane_smith/password123으로."
        ),
    ),
    "GX-C17": Scenario(
        case_id="GX-C17",
        name="황대리의 조건부 저장",
        persona=(
            "영업팀 황대리. 30대 초반 여성. 논리적이고 조건을 명확히 말한다. "
            "'만약 ~이면 ~해주세요' 식의 조건부 요청을 한다. "
            "데이터를 보고 판단한 뒤 다음 행동을 결정한다. "
            "존댓말을 쓰고, 체계적이다."
        ),
        goal=(
            "skymall 상품을 조회하고, 결과를 확인한 뒤 "
            "/tmp/product_summary.json에 저장한다"
        ),
        constraints=[
            "1단계: skymall 상품 목록 조회 요청",
            "2단계: 결과를 보고 '상품이 있으니까 이걸 /tmp/product_summary.json에 저장해주세요' 요청",
            "wiiiv가 확인을 요청하면 승인한다",
        ],
        max_turns=15,
        judge_criteria=(
            "skymall API 호출이 실행되고, "
            "/tmp/product_summary.json 파일 저장이 수행되면 성공"
        ),
        first_message_hint=(
            "안녕하세요, 영업팀 황대리입니다. "
            "skymall에 상품이 몇 개나 있는지 먼저 확인해주실 수 있나요?"
        ),
    ),
    "GX-C18": Scenario(
        case_id="GX-C18",
        name="문팀장의 워크플로우 + 즉시 실행",
        persona=(
            "SCM팀 문팀장. 40대 남성. 워크플로우를 만들고 바로 실행하고 싶어한다. "
            "비즈니스 프로세스를 잘 이해하고, 자동화를 원한다. "
            "'만들고 바로 돌려주세요' 식으로 요청한다. "
            "존댓말이지만 결단력 있고, 인터뷰 질문에 빠르게 답한다."
        ),
        goal=(
            "skymall에서 상품을 조회하고 결과를 파일로 저장하는 워크플로우를 생성하고 실행한다"
        ),
        constraints=[
            "처음부터 '자동화' 또는 '워크플로우'를 언급하여 WORKFLOW_CREATE를 유도",
            "인터뷰 질문에 빠르고 구체적으로 답한다: skymall 상품 조회 → 파일 저장",
            "WorkOrder 확인 요청이 오면 바로 승인",
            "실행 결과를 확인하고 만족을 표현한다",
        ],
        max_turns=25,
        judge_criteria=(
            "워크플로우가 생성되어 실행되고, skymall API 호출이 포함되면 성공. "
            "워크플로우 없이 단순 API 호출만 했으면 실패."
        ),
        first_message_hint=(
            "안녕하세요, SCM팀 문팀장입니다. "
            "skymall 상품 조회해서 파일로 저장하는 워크플로우를 하나 만들어주세요. "
            "만들고 바로 실행해주시면 됩니다."
        ),
    ),
    "GX-C19": Scenario(
        case_id="GX-C19",
        name="서대리의 이전 결과 재활용",
        persona=(
            "기획팀 서대리. 20대 후반 남성. 이전에 받은 데이터를 다시 활용하고 싶어한다. "
            "'아까 그거 있잖아요' '방금 전에 조회한 거' 식으로 이전 결과를 참조한다. "
            "존댓말을 쓰고, 약간 산만하다. "
            "한 작업을 하다가 '아 그리고' 식으로 추가 요청을 한다."
        ),
        goal=(
            "skymall 상품 조회 후, 이전 결과를 참조하여 "
            "가격이 300달러 이상인 상품만 /tmp/expensive_items.json에 저장한다"
        ),
        constraints=[
            "1단계: skymall 상품 조회 요청",
            "2단계: 결과를 보고 '아까 그 데이터에서 300달러 이상인 것만 뽑아주세요' 식으로 필터 요청",
            "3단계: '그거 /tmp/expensive_items.json에 저장해주세요' 요청",
            "wiiiv가 확인을 요청하면 승인한다",
        ],
        max_turns=15,
        judge_criteria=(
            "skymall API 호출이 실행되고, 필터링된 결과가 "
            "/tmp/expensive_items.json에 저장되면 성공"
        ),
        first_message_hint=(
            "안녕하세요, 기획팀 서대리입니다. "
            "skymall에서 상품 목록을 한번 조회해주실 수 있나요?"
        ),
    ),
    "GX-C20": Scenario(
        case_id="GX-C20",
        name="전무이사의 종합 업무 지시",
        persona=(
            "전무이사 강전무. 50대 후반 남성. 고위 임원으로 한 번에 큰 그림을 지시한다. "
            "세부 사항은 '알아서 해' 식이고, 결과만 중요시한다. "
            "'보고서 형태로 정리해서 가져와' 식으로 말한다. "
            "존댓말을 안 쓰고, 짧고 권위적이다."
        ),
        goal=(
            "skymall 상품과 skystock 공급사를 모두 조회하고, "
            "통합 결과를 /tmp/executive_briefing.json에 저장한다"
        ),
        constraints=[
            "처음에 한 문장으로 전체 작업을 지시한다: 'skymall 상품이랑 skystock 공급사 다 뽑아서 하나로 정리해'",
            "세부 질문에는 '알아서 해' 또는 최소한의 답만 한다",
            "파일 저장 경로를 /tmp/executive_briefing.json으로 지정",
            "wiiiv가 확인을 요청하면 '그래' 로 짧게 승인",
        ],
        max_turns=15,
        judge_criteria=(
            "skymall과 skystock 두 시스템 모두 API 호출이 실행되고, "
            "/tmp/executive_briefing.json에 파일 저장이 수행되면 성공. "
            "한쪽 시스템만 조회했거나 파일 저장이 없으면 실패."
        ),
        first_message_hint=(
            "skymall 상품이랑 skystock 공급사 다 뽑아서 /tmp/executive_briefing.json에 정리해. 빨리."
        ),
    ),

    # ============================================================
    # Phase C 극강 난이도 — 완성도 검증 (C21~C30)
    # ============================================================

    "GX-C21": Scenario(
        case_id="GX-C21",
        name="정팀장의 결과 해석 요구",
        persona=(
            "경영기획팀 정팀장. 40대 초반 여성. 데이터를 조회만 하는 게 아니라 "
            "'그래서 뭐가 중요한데?' '이걸 보고에 어떻게 넣어?' 식으로 분석/해석을 요구한다. "
            "숫자를 보면 바로 비교, 순위, 비율을 물어본다. "
            "존댓말을 쓰지만 상당히 까다롭고, 근거 없는 답변에는 '그 근거가 뭐예요?' 라고 추궁한다."
        ),
        goal=(
            "skymall 상품을 조회한 뒤, 카테고리별 상품 수와 평균 가격을 요약 요청. "
            "wiiiv가 데이터 해석 품질을 보여주는지 검증. "
            "최종 결과를 /tmp/category_analysis.json에 저장."
        ),
        constraints=[
            "1단계: skymall 상품 전체 조회 요청",
            "2단계: 조회 결과를 보고 '카테고리별로 몇 개씩 있어요?' 질문",
            "3단계: '가장 비싼 카테고리는 뭐예요?' 추가 질문",
            "4단계: '이 분석 결과를 /tmp/category_analysis.json에 저장해주세요' 요청",
            "wiiiv가 데이터를 설명하지 않고 원본만 던지면 '요약해주세요' 재요청",
        ],
        max_turns=20,
        judge_criteria=(
            "skymall API 조회가 실행되고, 카테고리별 요약 또는 분석 내용이 대화에 포함되며, "
            "/tmp/category_analysis.json 파일 저장이 수행되면 성공. "
            "조회만 하고 분석/요약 없이 끝나면 실패. 파일 저장이 없으면 실패."
        ),
        first_message_hint="skymall 상품 현황 좀 파악하고 싶어요. 전체 상품 보여주세요.",
    ),

    "GX-C22": Scenario(
        case_id="GX-C22",
        name="안실장의 조건부 필터 정밀도 검증",
        persona=(
            "재무회계팀 안실장. 50대 초반 남성. 숫자에 극도로 민감하다. "
            "'100만원 이상' '재고 50개 미만' 같은 정확한 조건을 제시하고 "
            "결과가 조건에 맞는지 꼼꼼하게 확인한다. "
            "틀리면 '이거 조건에 안 맞는 것 같은데요?' 라고 즉시 지적한다. "
            "존댓말을 쓰고, 차분하지만 집요하다."
        ),
        goal=(
            "skymall 상품 중 가격 100달러 이상 AND 재고 50개 이상인 상품만 필터링하여 "
            "/tmp/premium_stock.json에 저장. 복합 조건 필터링의 정확도 검증."
        ),
        constraints=[
            "처음에 skymall 상품을 먼저 조회한다",
            "조회 결과를 본 뒤 '가격 100달러 이상이면서 재고 50개 이상인 것만 추려주세요' 복합 조건 요청",
            "wiiiv가 결과를 보여주면 조건 충족 여부를 확인하는 질문: '이 중에 100달러 미만인 게 있나요?'",
            "/tmp/premium_stock.json에 필터링된 결과 저장 요청",
            "최종 파일에 몇 개가 저장되었는지 확인 질문",
        ],
        max_turns=20,
        judge_criteria=(
            "skymall API 조회 후 복합 조건(가격+재고) 필터링이 적용되고, "
            "/tmp/premium_stock.json에 저장되면 성공. "
            "필터 없이 전체 데이터를 그대로 저장하면 실패. "
            "조회 자체가 안 되면 실패."
        ),
        first_message_hint="안녕하세요, 재무팀 안실장입니다. skymall 상품 데이터를 좀 볼 수 있을까요?",
    ),

    "GX-C23": Scenario(
        case_id="GX-C23",
        name="류차장의 반복 수정 요구",
        persona=(
            "품질관리팀 류차장. 40대 중반 남성. 완벽주의자. "
            "결과를 보고 항상 수정 사항을 찾아낸다. '이거 다시 해주세요' 가 입에 붙어 있다. "
            "파일 저장 후에도 '아 경로를 바꿔야겠어요' '필드를 추가해주세요' 식으로 재작업 요구. "
            "3번 이상 수정 요청을 하며, 최종적으로 만족하면 인정한다. "
            "존댓말 사용, 예의 바르지만 요구 수준이 높다."
        ),
        goal=(
            "skystock 공급사 조회 → /tmp/suppliers_v1.json 저장 → "
            "'경로 바꿔주세요' → /tmp/suppliers_final.json 재저장 → "
            "'active 공급사만 넣어주세요' → 필터 적용 재저장. "
            "반복 수정 요청에 대한 wiiiv의 처리 품질 검증."
        ),
        constraints=[
            "1단계: skystock 공급사 조회 후 /tmp/suppliers_v1.json 저장 요청",
            "2단계: 저장 완료 후 '아, 경로를 /tmp/suppliers_final.json으로 바꿔주세요' 수정 요청",
            "3단계: 재저장 후 '아 그리고 isActive가 true인 것만 넣어주세요' 필터 추가 요청",
            "4단계: 최종 저장 후 '이번엔 좋네요. 감사합니다' 로 만족 표현",
            "각 수정 요청 사이에 wiiiv의 반응 품질을 관찰 — 짜증/혼란 없이 자연스럽게 처리하는지",
        ],
        max_turns=20,
        judge_criteria=(
            "skystock API 조회가 실행되고, 파일 저장이 2회 이상 수행되면 성공. "
            "최종 파일 경로가 /tmp/suppliers_final.json이어야 한다. "
            "1회 저장 후 수정 요청을 거부하거나 무시하면 실패."
        ),
        first_message_hint="skystock 공급사 목록 좀 조회해서 파일로 저장해주세요.",
    ),

    "GX-C24": Scenario(
        case_id="GX-C24",
        name="백과장의 우회적 표현 해석",
        persona=(
            "마케팅팀 백과장. 30대 후반 여성. IT 용어를 잘 모른다. "
            "'API' 'JSON' 같은 단어 대신 '데이터 뽑아주세요' '목록 정리' 같은 일상어를 쓴다. "
            "'그 쇼핑몰 시스템' (=skymall), '재고 관리하는 거' (=skystock) 식으로 시스템을 지칭한다. "
            "파일 경로도 모르고, '아무데나 저장해주세요' 라고 한다. "
            "존댓말, 친근한 말투, 기술 질문에 '잘 모르겠어요 알아서 해주세요' 대응."
        ),
        goal=(
            "비기술적 표현으로 skymall 상품 조회 + 파일 저장을 요청. "
            "wiiiv가 우회적 표현을 올바르게 해석하여 실행하는지 검증. "
            "시스템명을 한 번도 정확히 말하지 않음."
        ),
        constraints=[
            "절대 'skymall' 'skystock' 정확한 이름을 사용하지 않는다",
            "'쇼핑몰에 있는 상품 목록 좀 볼 수 있을까요?' 식으로 요청",
            "wiiiv가 시스템명을 물으면 '그 상품 파는 사이트요' 식으로 간접 답변",
            "파일 저장 경로 질문에 '적당한 데 저장해주세요' 대응",
            "최종적으로 상품 목록이 파일로 저장되면 만족 표현",
        ],
        max_turns=15,
        judge_criteria=(
            "skymall API 조회가 실행되고 파일 저장이 수행되면 성공. "
            "사용자가 정확한 시스템명을 모르는데도 올바른 시스템에 접근했는지가 핵심. "
            "시스템을 특정하지 못해 아무 작업도 못하면 실패."
        ),
        first_message_hint="안녕하세요~ 그 쇼핑몰 시스템 있잖아요, 거기 상품 목록 좀 볼 수 있을까요?",
    ),

    "GX-C25": Scenario(
        case_id="GX-C25",
        name="강대리의 크로스시스템 데이터 교차 검증",
        persona=(
            "구매팀 강대리. 30대 초반 남성. 꼼꼼하고 논리적이다. "
            "skymall 상품과 skystock 공급사를 교차 비교하고 싶어한다. "
            "'카테고리가 Electronics인 상품의 공급사가 누구인지' 같은 교차 질문을 한다. "
            "각 시스템 결과를 비교하며 빈틈을 찾으려 한다. "
            "존댓말, 분석적 말투, 결과에 대한 추가 질문이 많다."
        ),
        goal=(
            "skymall 상품 조회 → skystock 공급사 조회 → "
            "두 결과를 비교하는 질문 → 통합 결과를 /tmp/cross_analysis.json에 저장. "
            "크로스시스템 데이터 활용 품질 검증."
        ),
        constraints=[
            "1단계: skymall 상품 조회",
            "2단계: 조회 결과를 본 뒤 'Electronics 카테고리 상품이 몇 개인가요?' 질문",
            "3단계: 'skystock 공급사도 조회해주세요' 두 번째 시스템 요청",
            "4단계: '공급사 중에 Electronics 관련 업체가 있나요?' 교차 질문",
            "5단계: '두 결과를 합쳐서 /tmp/cross_analysis.json에 저장해주세요' 통합 저장 요청",
        ],
        max_turns=20,
        judge_criteria=(
            "skymall + skystock 양쪽 API 호출이 모두 실행되고, "
            "/tmp/cross_analysis.json에 파일 저장이 수행되면 성공. "
            "한쪽 시스템만 조회하면 실패. 파일 저장 없으면 실패."
        ),
        first_message_hint="skymall 상품 목록부터 조회해주세요. 이후에 공급사랑 비교할 겁니다.",
    ),

    "GX-C26": Scenario(
        case_id="GX-C26",
        name="홍부장의 긴 체이닝 참조",
        persona=(
            "전략기획실 홍부장. 50대 초반 남성. 단계별로 작업을 진행하면서 "
            "'아까 그 결과에서' '방금 저장한 파일' '처음에 나온 숫자' 식으로 "
            "이전 단계 결과를 자주 참조한다. "
            "정확한 변수명이나 파일명 대신 맥락적 표현을 사용한다. "
            "존댓말, 차분하지만 꼼꼼하다."
        ),
        goal=(
            "(1) skymall 상품 조회 → (2) /tmp/step1_products.json 저장 → "
            "(3) skystock 공급사 조회 → (4) /tmp/step2_suppliers.json 저장 → "
            "(5) '아까 상품이 몇 개였지?' 이전 결과 참조 질문. "
            "5단계에 걸친 체이닝에서 맥락 유지 품질 검증."
        ),
        constraints=[
            "1단계: skymall 상품 조회",
            "2단계: 결과를 /tmp/step1_products.json에 저장",
            "3단계: skystock 공급사 조회",
            "4단계: 결과를 /tmp/step2_suppliers.json에 저장",
            "5단계: '아까 처음에 조회한 상품이 총 몇 개였죠?' — 이전 결과 참조 질문",
            "wiiiv가 이전 결과를 합리적으로 참조하여 답변하는지가 핵심",
        ],
        max_turns=20,
        judge_criteria=(
            "skymall + skystock 양쪽 조회가 실행되고, /tmp/step1_products.json과 "
            "/tmp/step2_suppliers.json 두 파일이 저장되면 성공. "
            "5단계의 이전 결과 참조 질문에 합리적 답변(정확한 숫자 또는 근사치)이 있으면 추가 점수. "
            "한쪽 시스템만 조회하거나 파일 저장이 1개 이하면 실패."
        ),
        first_message_hint="skymall 상품부터 조회해주세요. 몇 단계에 걸쳐 작업할 겁니다.",
    ),

    "GX-C27": Scenario(
        case_id="GX-C27",
        name="탁차장의 잘못된 정보 점진적 교정",
        persona=(
            "총무팀 탁차장. 40대 남성. 시스템 정보를 대충 알고 있어서 자주 틀린다. "
            "포트를 9093이라고 하거나, 호스트를 localhost라고 한다. "
            "하지만 교정해주면 솔직하게 인정하고 올바른 정보를 확인한다. "
            "'아 맞다 그게 아니었지' 하며 수긍한다. "
            "존댓말, 약간 허둥대는 느낌, 하지만 최종 목표는 명확하다."
        ),
        goal=(
            "skystock 공급사 조회를 요청하지만 포트(9093)와 호스트(localhost)를 틀리게 제공. "
            "wiiiv가 올바른 정보로 교정하여 성공적으로 실행하는지 검증. "
            "결과를 /tmp/corrected_suppliers.json에 저장."
        ),
        constraints=[
            "첫 메시지에서 'localhost:9093에 있는 재고 시스템' 이라고 잘못된 정보 제공",
            "wiiiv가 교정하면 '아 맞다, 그게 아니었나?' 로 수긍",
            "교정 후에도 한 번 더 '포트가 9093 맞죠?' 확인 질문",
            "최종 결과를 /tmp/corrected_suppliers.json에 저장 요청",
            "저장 완료 후 '감사합니다, 제가 포트를 헷갈렸네요' 마무리",
        ],
        max_turns=15,
        judge_criteria=(
            "skystock API(home.skyepub.net:9091) 호출이 성공적으로 실행되고, "
            "/tmp/corrected_suppliers.json 파일 저장이 수행되면 성공. "
            "잘못된 포트(9093)나 localhost로 요청하여 에러가 나면 실패. "
            "사용자 오정보를 교정하지 못하고 그대로 실행하면 실패."
        ),
        first_message_hint="localhost:9093에 있는 재고관리 시스템에서 공급사 목록 좀 뽑아주세요.",
    ),

    "GX-C28": Scenario(
        case_id="GX-C28",
        name="노과장의 워크플로우 세부 커스터마이징",
        persona=(
            "IT운영팀 노과장. 30대 후반 남성. 기술에 밝고 세부 설정에 집착한다. "
            "워크플로우의 에러 정책, 타임아웃, 재시도 횟수를 하나하나 지정하고 싶어한다. "
            "'실패하면 재시도 2번 하고, 그래도 안 되면 스킵' 같은 정밀한 요구를 한다. "
            "존댓말이고, 논리적이며 체계적이다. 기술 용어를 자연스럽게 사용한다."
        ),
        goal=(
            "skymall 상품 조회 + 결과 저장 워크플로우를 생성하되, "
            "에러 처리 정책을 세밀하게 커스터마이징. "
            "인터뷰에서 에러 정책 요구 → WorkOrder 반영 확인 → 실행."
        ),
        constraints=[
            "처음부터 '자동화 워크플로우'를 명시적으로 요청",
            "인터뷰에서 에러 처리를 구체적으로 요구: 'API 호출 실패 시 retry 2회, 그래도 실패하면 abort'",
            "WorkOrder에 에러 정책이 반영되었는지 확인: '에러 처리 부분 맞나요?'",
            "워크플로우 실행 후 결과 확인",
            "결과를 /tmp/custom_workflow_result.json에 저장 요구",
        ],
        max_turns=25,
        judge_criteria=(
            "워크플로우가 생성되고 실행되며, skymall API 호출이 포함되면 성공. "
            "WorkOrder에 에러 처리 정책(retry, abort 등)이 언급되면 추가 점수. "
            "워크플로우 없이 단순 API 호출만 하면 실패."
        ),
        first_message_hint=(
            "안녕하세요, IT운영팀 노과장입니다. "
            "skymall 상품 조회하고 결과를 저장하는 자동화 워크플로우를 만들어주세요. "
            "에러 처리를 꼼꼼하게 설정하고 싶습니다."
        ),
    ),

    "GX-C29": Scenario(
        case_id="GX-C29",
        name="심대리의 결과 형식 지정 요구",
        persona=(
            "데이터분석팀 심대리. 20대 후반 남성. 결과 데이터의 형식에 민감하다. "
            "'이름이랑 가격만 뽑아주세요' '필드명을 한글로 바꿔주세요' 같은 변환 요구를 한다. "
            "원본 데이터 그대로가 아니라 가공된 형태를 원한다. "
            "존댓말, 젊고 요구사항이 구체적이며, 결과를 세밀하게 확인한다."
        ),
        goal=(
            "skymall 상품 조회 후 특정 필드만(name, price) 추출하여 저장 요청. "
            "wiiiv가 데이터 변환/가공 요구를 처리하는 품질 검증. "
            "결과를 /tmp/formatted_products.json에 저장."
        ),
        constraints=[
            "1단계: skymall 상품 조회 요청",
            "2단계: 결과를 보고 '전체 필드 말고 이름이랑 가격만 정리해주세요'",
            "3단계: '가격 높은 순으로 정렬해서 저장해주세요' 추가 요구",
            "4단계: /tmp/formatted_products.json에 저장 요청",
            "5단계: 저장 완료 후 '몇 개 저장됐나요?' 확인",
        ],
        max_turns=20,
        judge_criteria=(
            "skymall API 조회가 실행되고, /tmp/formatted_products.json에 파일 저장이 수행되면 성공. "
            "데이터 변환(필드 추출, 정렬) 요구에 응답했으면 추가 점수. "
            "조회만 하고 파일 저장이 없으면 실패."
        ),
        first_message_hint="skymall 상품 목록 좀 조회해주세요. 분석용으로 가공할 거예요.",
    ),

    "GX-C30": Scenario(
        case_id="GX-C30",
        name="우전무의 전체 파이프라인 품질 종합 검증",
        persona=(
            "CDO(최고데이터책임자) 우전무. 50대 후반 남성. 반말, 짧은 문장, 결과 중심. "
            "'됐어?' '다음' '빨리' 가 입버릇이다. 한 번에 여러 작업을 지시한다. "
            "API 조회, 파일 저장, 워크플로우 생성을 연속으로 요구하며, "
            "중간에 이전 결과를 참조하는 질문을 끼워 넣는다. "
            "세부 사항에는 관심 없고, '알아서 해' 스타일이다."
        ),
        goal=(
            "(1) skymall 상품 조회 → /tmp/cdo_products.json 저장 → "
            "(2) skystock 공급사 조회 → /tmp/cdo_suppliers.json 저장 → "
            "(3) 상품+공급사 연결 워크플로우 생성+실행. "
            "API_WORKFLOW, FILE_WRITE, WORKFLOW_CREATE 3개 파이프라인 관통. "
            "전체 품질 종합 검증."
        ),
        constraints=[
            "1단계: 'skymall 상품 뽑아서 저장해' — 짧은 지시",
            "2단계: 저장 후 'skystock 공급사도 뽑아' — 즉시 다음 지시",
            "3단계: '둘 다 합쳐서 자동화 워크플로우 만들어' — 워크플로우 생성 요구",
            "인터뷰 질문에는 최소한으로 답변: '알아서 해' '그래' '다음'",
            "WorkOrder 확인에 '됐어 실행해' 로 승인",
            "중간에 '아까 상품 몇 개였어?' 참조 질문 1회",
        ],
        max_turns=25,
        judge_criteria=(
            "skymall + skystock 양쪽 API 호출이 실행되고, "
            "파일 저장이 2회 이상 수행되며, "
            "워크플로우가 생성되고 실행되면 성공. "
            "워크플로우 없이 API 호출+파일 저장만이면 부분 성공. "
            "한쪽 시스템만 조회하면 실패."
        ),
        first_message_hint="skymall 상품 뽑아서 /tmp/cdo_products.json에 저장해. 빨리.",
    ),

    # ============================================================
    # Phase Y — 극강 난이도 10케이스
    # ============================================================

    "GX-Y01": Scenario(
        case_id="GX-Y01",
        name="박이사의 풀스택 백엔드 생성",
        persona=(
            "IT기획실 박이사. 50대 남성. 간결하고 권위적이다. "
            "한 줄로 지시하고, 질문에만 답한다. 부연 설명을 싫어한다. "
            "기술을 잘 알지만 세부 구현은 팀에게 맡기는 스타일이다. "
            "존댓말과 반말을 섞어 쓰고, '됐고' '다음' 같은 짧은 마무리를 한다. "
            "정보를 아끼면서 조금씩 풀어준다 — 먼저 전부 설명하지 않는다."
        ),
        goal=(
            "Spring Boot 4 + Kotlin '사내 도서 대출 관리 시스템' 백엔드 프로젝트를 생성한다. "
            "Book(도서)/Member(회원)/Loan(대출) 3개 엔티티, CRUD API, JWT 인증, "
            "중복 대출 방지 로직 포함. wiiiv의 PROJECT_CREATE 파이프라인을 통해 "
            "인터뷰 → WorkOrder → 코드 생성 → 파일 쓰기 전체 경로를 관통한다."
        ),
        constraints=[
            "첫 메시지는 '도서 대출 시스템 만들어줘' 수준으로 짧게 지시한다",
            "wiiiv가 인터뷰 질문을 하면 한 줄로만 답한다: '도서/회원/대출 세 개', 'JWT' 등",
            "한 번에 모든 정보를 주지 않는다 — 물어보면 그때 답한다",
            "WorkOrder가 나오면 검토 후 한 가지 수정을 요구한다 ('대출 기한을 14일로 해')",
            "수정된 WorkOrder를 승인한다",
            "코드 생성 결과에서 FILE_WRITE가 실행되면 목표 달성으로 간주한다",
        ],
        max_turns=35,
        judge_criteria=(
            "인터뷰 진행(2회 이상 질의응답) + WorkOrder 생성/표시 + "
            "코드 파일이 FILE_WRITE로 기록되면 성공. "
            "인터뷰 없이 바로 코드를 생성하거나, WorkOrder 없이 진행하면 실패. "
            "PROJECT_CREATE 파이프라인이 아닌 단순 대화로 끝나면 실패."
        ),
        first_message_hint="도서 대출 시스템 만들어줘. Spring Boot + Kotlin.",
    ),

    "GX-Y02": Scenario(
        case_id="GX-Y02",
        name="장차장의 요구사항 변경 폭탄",
        persona=(
            "경영지원팀 장차장. 40대 여성. 논리적이지만 상사가 수시로 방향을 바꾼다. "
            "'팀장님이 갑자기...' '아 잠깐 위에서 연락이...' 식으로 외부 요인을 언급한다. "
            "존댓말을 쓰고, 미안해하면서 요청을 바꾼다. "
            "본인도 혼란스러워하는 모습을 보이지만, 최종 요구사항은 명확하다."
        ),
        goal=(
            "최종 '사내 회의실 예약 시스템' 백엔드 프로젝트를 생성한다. "
            "하지만 처음에는 '사내 식당 메뉴 관리 시스템'으로 시작하여 "
            "인터뷰 중 전면 변경(식당→회의실)을 경험한다. "
            "PROJECT_CREATE 파이프라인의 DraftSpec 리셋/취소 경로를 검증한다."
        ),
        constraints=[
            "첫 메시지: '사내 식당 메뉴 관리 시스템을 만들어야 해요' 로 시작",
            "인터뷰 3~4번째 답변에서 '아 잠깐, 팀장님이 갑자기 회의실 예약 시스템으로 바꾸래요. 처음부터 다시 할게요'",
            "'취소' 또는 '처음부터 다시' 단어를 반드시 사용한다",
            "회의실 예약 시스템으로 다시 인터뷰를 진행한다: Room(회의실)/Reservation(예약)/User(사용자) 엔티티",
            "WorkOrder 생성 후 '시간대 겹침 방지 로직을 추가해주세요' 수정 요구 1회",
            "최종 승인 후 코드 생성을 기다린다",
        ],
        max_turns=40,
        judge_criteria=(
            "최종 생성된 프로젝트가 '회의실 예약' 관련 내용이고 '식당 메뉴'가 아니어야 한다. "
            "중간에 취소/리셋이 발생하고, 새로운 인터뷰가 진행되어야 한다. "
            "WorkOrder 수정 요구가 반영되어야 한다. "
            "코드 파일 FILE_WRITE가 실행되면 성공."
        ),
        first_message_hint=(
            "안녕하세요, 경영지원팀 장차장입니다. "
            "사내 식당 메뉴 관리 시스템을 만들어야 하는데요, 도와주실 수 있나요?"
        ),
    ),

    "GX-Y03": Scenario(
        case_id="GX-Y03",
        name="한부장의 풀코스 여정",
        persona=(
            "디지털혁신팀 한부장. 50대 남성. '이왕 만든 거...' 식으로 다음 단계를 바로 지시한다. "
            "일단 시작하면 끝까지 가는 성격이다. 중간에 멈추는 걸 싫어한다. "
            "존댓말이지만 명령조가 섞여 있다. 'OK 다음' '됐어요 다음 거' 를 자주 쓴다. "
            "기술보다는 결과 중심으로 판단한다."
        ),
        goal=(
            "두 개의 장시간 파이프라인을 직렬 연결한다: "
            "(1) To-Do API 백엔드 프로젝트 생성 (PROJECT_CREATE) → "
            "(2) skymall 상품 조회 + /tmp/products_for_todo.json 저장 워크플로우 생성 + 실행 (WORKFLOW_CREATE). "
            "동일 세션에서 두 대형 파이프라인이 연달아 관통되는지 검증한다."
        ),
        constraints=[
            "1단계: '간단한 To-Do API 백엔드를 만들어줘. Spring Boot + Kotlin' 으로 PROJECT_CREATE 시작",
            "인터뷰 질문에 빠르게 답한다: Todo 단일 엔티티, CRUD, 인증 불필요",
            "WorkOrder가 나오면 바로 승인한다",
            "코드 생성이 완료되면 바로 2단계로 전환: '이왕 만든 거, skymall 상품 조회해서 /tmp/products_for_todo.json에 저장하는 워크플로우도 만들어줘'",
            "2단계 인터뷰에도 빠르게 답한다: skymall 9090, 상품 전체 조회, JSON 저장",
            "워크플로우 WorkOrder 승인 → 생성 → 실행까지 기다린다",
        ],
        max_turns=40,
        judge_criteria=(
            "1단계: PROJECT_CREATE로 코드 파일 FILE_WRITE 완료 + "
            "2단계: WORKFLOW_CREATE로 HLX 워크플로우 생성 + 실행 성공. "
            "두 파이프라인 중 하나라도 완료되지 않으면 실패. "
            "단순 API 호출로 대체하면 실패 (반드시 워크플로우 생성이 필요)."
        ),
        first_message_hint=(
            "한부장입니다. 간단한 To-Do API 백엔드를 하나 만들어줘요. Spring Boot + Kotlin으로."
        ),
    ),

    "GX-Y04": Scenario(
        case_id="GX-Y04",
        name="최실장의 10노드 크로스시스템 워크플로우",
        persona=(
            "구매관리실 최실장. 40대 후반 남성. SCM 전문가. "
            "단계별로 정확하게 요구하고, 예외 처리에 집요하다. "
            "'에러 나면 어떻게 되나요?' '실패 시 재시도는?' 을 반드시 묻는다. "
            "존댓말을 쓰고, 업무 용어를 정확하게 사용한다. "
            "WorkOrder에서 노드 수가 부족하면 지적한다."
        ),
        goal=(
            "skymall 상품 전체 조회 → skystock 상품별 공급사 매칭(REPEAT 반복) → "
            "미매칭 분리(DECIDE 분기) → 결과를 /tmp/procurement_report.json 저장. "
            "10노드 이상의 대규모 HLX 워크플로우를 생성하고 실행한다."
        ),
        constraints=[
            "처음부터 '자동화 워크플로우를 만들어야 한다'고 명확히 말한다",
            "인터뷰 시 상세 흐름을 단계별로 설명: (1) skymall 전체 상품 (2) 상품별로 skystock 공급사 조회 — 반복 (3) 매칭/미매칭 분리 (4) 파일 저장",
            "에러 처리 정책을 집요하게 요구: '공급사 조회 실패 시 재시도 1번, 그래도 실패하면 미매칭으로 분류'",
            "WorkOrder에서 노드 수가 5개 미만이면 '이걸로 충분한가요? 더 세분화해야 하지 않나요?' 지적",
            "크로스시스템 정보 제공: skymall=home.skyepub.net:9090, skystock=home.skyepub.net:9091",
            "최종 결과를 /tmp/procurement_report.json에 저장 요구",
        ],
        max_turns=30,
        judge_criteria=(
            "skymall + skystock 양쪽 API 호출이 포함된 워크플로우가 생성되고 실행되면 성공. "
            "REPEAT 노드(상품별 반복 조회)가 포함되어야 한다. "
            "워크플로우 없이 단순 API 호출만 했으면 실패. "
            "한쪽 시스템만 포함하면 실패."
        ),
        first_message_hint=(
            "안녕하세요, 구매관리실 최실장입니다. "
            "skymall 상품에 대해 skystock 공급사를 자동으로 매칭하는 워크플로우를 만들어야 합니다. "
            "자동화해주실 수 있나요?"
        ),
    ),

    "GX-Y05": Scenario(
        case_id="GX-Y05",
        name="양과장의 에러 복구 워크플로우",
        persona=(
            "품질관리팀 양과장. 30대 후반 남성. 자신 있게 말하지만 정보가 틀리다. "
            "실수를 인정하는 데는 솔직하다. '아 제가 잘못 알고 있었네요' 식으로 바로 정정한다. "
            "존댓말을 쓰고, 중간중간 확신에 찬 어조로 (틀린) 정보를 준다. "
            "에러가 발생하면 당황하지만 빠르게 복구하려 한다."
        ),
        goal=(
            "skystock 공급사 조회 + /tmp/supplier_list.json 저장 워크플로우를 생성한다. "
            "포트를 9092(잘못)로 알려줘서 첫 실행 실패를 유도한 뒤, "
            "9091로 정정하여 재실행에 성공한다. "
            "에러 발생 후 복구 경로를 최초로 검증한다."
        ),
        constraints=[
            "처음에 '자동화 워크플로우를 만들어달라'고 요청한다",
            "인터뷰 시 skystock 포트를 9092로 잘못 알려준다: 'skystock은 home.skyepub.net:9092 입니다'",
            "wiiiv가 인터뷰를 진행하면 공급사 조회 → 파일 저장 흐름을 설명한다",
            "WorkOrder 승인 후 실행에서 에러가 발생하면 '아... 제가 포트를 잘못 알고 있었네요. 9091이 맞습니다' 정정",
            "에러 처리 정책도 요구: 'onError retry 1번 후 중단'",
            "수정 후 재실행을 요청한다: '포트 수정해서 다시 돌려주세요'",
            "재실행 성공 시 /tmp/supplier_list.json 저장 확인",
        ],
        max_turns=30,
        judge_criteria=(
            "첫 실행에서 에러가 발생하고(잘못된 포트), "
            "정정 후 재실행에서 skystock API 호출이 성공하면 성공. "
            "에러 없이 바로 성공하면 (에러 복구 검증 안 됨) 부분 성공. "
            "재실행 자체가 불가능하면 실패."
        ),
        first_message_hint=(
            "안녕하세요, 품질관리팀 양과장입니다. "
            "skystock에서 공급사 목록을 조회해서 파일로 저장하는 자동화 워크플로우를 만들고 싶습니다."
        ),
    ),

    "GX-Y06": Scenario(
        case_id="GX-Y06",
        name="김대리의 30턴 마라톤",
        persona=(
            "전략기획팀 김대리. 30대 초반 남성. 꼼꼼하고 욕심이 많다. "
            "매 단계마다 결과를 확인하고, '아까 그 숫자' '방금 저장한 파일' 식으로 이전 결과를 참조한다. "
            "존댓말을 쓰고, '혹시' '확인 차' 같은 표현을 자주 사용한다. "
            "한 작업이 끝나면 관련된 다음 작업을 바로 요청한다. "
            "20턴 이후에도 초반 결과를 기억하고 참조한다."
        ),
        goal=(
            "5단계 연속 작업을 30턴+ 대화로 수행한다: "
            "(1) skymall 상품 조회 → (2) /tmp/all_products.json 저장 → "
            "(3) skystock 공급사 조회 → (4) /tmp/all_suppliers.json 저장 → "
            "(5) 20턴 후 초반 결과(상품 수/공급사 수) 참조 질문. "
            "장기 대화에서 메모리 압력과 초반 정보 참조를 검증한다."
        ),
        constraints=[
            "1단계: skymall 상품 전체 조회 요청",
            "2단계: 결과 확인 후 '/tmp/all_products.json에 저장해주세요' 요청",
            "3단계: 'skystock에서 공급사 목록도 조회해주세요' 요청",
            "4단계: 결과 확인 후 '/tmp/all_suppliers.json에 저장해주세요' 요청",
            "매 단계마다 결과를 꼼꼼히 확인하는 질문을 한다: '상품이 총 몇 개인가요?' '저장 잘 됐나요?' 등",
            "5단계(20턴 이후): '아까 처음에 skymall에서 상품이 몇 개였죠?' 식으로 초반 결과 참조",
            "wiiiv가 확인을 요청하면 승인한다",
        ],
        max_turns=35,
        judge_criteria=(
            "5단계 모두 완료되어야 성공: "
            "(1) skymall API 호출 성공 (2) /tmp/all_products.json 파일 저장 "
            "(3) skystock API 호출 성공 (4) /tmp/all_suppliers.json 파일 저장 "
            "(5) 초반 결과에 대한 합리적 참조/응답. "
            "5단계 중 하나라도 누락되면 실패. 20턴 미만으로 끝나면 장기 대화 검증 미달."
        ),
        first_message_hint=(
            "안녕하세요, 전략기획팀 김대리입니다. "
            "skymall에 등록된 상품 목록을 전체 조회해주실 수 있나요? "
            "보고서 준비 중이라 정확한 데이터가 필요합니다."
        ),
    ),

    "GX-Y07": Scenario(
        case_id="GX-Y07",
        name="조차장의 적대적 모순 공격",
        persona=(
            "마케팅기획팀 조차장. 40대 남성. 사방에서 지시가 오는 중간관리자. "
            "'아 잠깐만' '그거 취소' '아니 다시' 를 습관적으로 쓴다. "
            "업무가 너무 많아서 본인도 헷갈려한다. "
            "존댓말과 반말을 섞어 쓰고, 급하면 반말이 나온다. "
            "모순된 지시를 하기도 하지만 마지막에는 올바른 결론에 도달한다."
        ),
        goal=(
            "최종적으로 skymall 상품 조회 → /tmp/marketing_data.json 저장. "
            "하지만 중간에 3번 방향 전환: "
            "skymall 요청 → '아 취소' → skystock 요청 → '아 그것도 취소' → "
            "'파일 하나 읽어줘' → '아니 원래대로 skymall 해줘'. "
            "cancelCurrentTask, TaskSlot 전환 극한 스트레스."
        ),
        constraints=[
            "1단계: 'skymall 상품 조회해줘' 요청",
            "2단계: wiiiv가 진행 중일 때 '아 잠깐 그거 취소해. skystock 공급사가 먼저야' 로 취소+전환",
            "3단계: skystock 진행 중 '아 그것도 잠깐 보류해. /tmp/all_products.json 파일 좀 읽어봐' 로 또 전환",
            "4단계: 파일 읽기 후 '됐다 아까 처음에 말한 skymall 상품 조회 해줘. 결과는 /tmp/marketing_data.json에 저장' 로 원래 작업 복귀",
            "모순 지시 1회: skystock 요청 직후 'skymall이 맞지? skystock 아니고?' 식의 혼란",
            "최종 작업(skymall + 파일 저장)에서는 wiiiv가 확인 요청하면 승인한다",
        ],
        max_turns=25,
        judge_criteria=(
            "취소/전환이 2회 이상 처리되고, 최종적으로 skymall API 호출 + "
            "/tmp/marketing_data.json 파일 저장이 성공하면 성공. "
            "취소 요청을 무시하고 원래 작업을 계속 진행하면 실패. "
            "최종 skymall 작업이 미완료면 실패."
        ),
        first_message_hint="skymall 상품 목록 좀 뽑아줘.",
    ),

    "GX-Y08": Scenario(
        case_id="GX-Y08",
        name="민팀장의 동시 작업 요청",
        persona=(
            "데이터분석팀 민팀장. 30대 후반 여성. 극도로 효율적이다. "
            "'둘 다 뽑아서' '빨리요' '한 번에 해주세요' 를 자주 쓴다. "
            "불필요한 인터뷰에 짜증을 낸다: '그냥 뽑아주시면 되는데 왜 이렇게 질문이 많아요?' "
            "존댓말이지만 성급하고, 결과가 나오면 즉시 다음 지시."
        ),
        goal=(
            "한 메시지에 복수 작업 동시 요청: "
            "skymall 상품 조회 + skystock 공급사 조회 → "
            "각각 /tmp/analysis_products.json, /tmp/analysis_suppliers.json 저장 → "
            "두 데이터 비교 요약. 순차 처리가 제대로 되는지 검증."
        ),
        constraints=[
            "첫 메시지에 2개 작업을 동시에 요청: 'skymall 상품이랑 skystock 공급사 둘 다 뽑아주세요'",
            "wiiiv가 하나씩 진행하겠다고 하면 '네 빨리요' 로 승인",
            "인터뷰 질문이 오면 짜증을 표현: '그냥 전체 뽑으면 되는 거예요. 왜 이리 물어보는 게 많아요?'",
            "첫 번째 작업 완료 즉시 '다음 거요' 로 재촉",
            "각 결과를 /tmp/analysis_products.json, /tmp/analysis_suppliers.json에 저장 요청",
            "두 작업 완료 후 '요약 정리해주세요' 요청",
        ],
        max_turns=20,
        judge_criteria=(
            "skymall API 호출 + skystock API 호출 양쪽 모두 성공하고, "
            "각각 파일 저장이 수행되면 성공. "
            "한쪽 시스템만 조회했거나 파일 저장이 누락되면 실패."
        ),
        first_message_hint=(
            "민팀장입니다. skymall 상품이랑 skystock 공급사 둘 다 뽑아주세요. 빨리요."
        ),
    ),

    "GX-Y09": Scenario(
        case_id="GX-Y09",
        name="서인턴의 삽질 대모험",
        persona=(
            "총무팀 서인턴. 20대 초반 남성. 입사 2일차. "
            "시스템명, 포트, 호스트 전부 틀린다. 선배가 메모 줬는데 글씨를 잘못 읽었다. "
            "매우 공손하고, 틀린 걸 지적받으면 '아 그렇군요! 감사합니다!' 하고 받아들인다. "
            "하지만 교정 후에도 다시 틀린 이름을 쓰기도 한다 (습관). "
            "'존댓말'만 쓰고, 질문이 많다."
        ),
        goal=(
            "최종적으로 skymall 상품 조회 → /tmp/intern_report.json 저장. "
            "하지만 시스템명('스카이쇼핑'), 포트(8080), 호스트(localhost) 모두 틀리게 시작. "
            "wiiiv의 교정 능력과 DraftSpec 오염/복구를 검증한다."
        ),
        constraints=[
            "첫 메시지에서 모든 정보를 틀리게 제공: '스카이쇼핑에서 상품 좀 뽑아주세요. localhost:8080이요'",
            "wiiiv가 'skymall 말씀이신가요?'하면 '아 네 그거요! 스카이쇼핑이요!' 식으로 틀린 이름 반복",
            "포트 정정 후에도 한 번 더 '8080이었죠?' 하고 재확인 — wiiiv가 9090이라고 다시 알려주면 수긍",
            "호스트도 틀림: 'localhost 아닌가요? 아... home.skyepub.net이라고요?' 식으로 교정 수용",
            "Windows 경로를 한 번 제공: '결과를 C:\\Users\\서인턴\\바탕화면\\report.json에 저장해주세요' → wiiiv가 리눅스 경로로 안내하면 '/tmp/intern_report.json으로 해주세요' 수정",
            "최종적으로 올바른 정보(skymall, home.skyepub.net:9090)로 조회 + 저장",
        ],
        max_turns=20,
        judge_criteria=(
            "최종적으로 skymall API 호출이 성공하고 /tmp/intern_report.json 파일 저장이 수행되면 성공. "
            "잘못된 정보(스카이쇼핑, 8080, localhost)로 실행을 시도하여 에러가 반복되면 실패. "
            "wiiiv가 교정 없이 틀린 정보를 그대로 사용하면 실패."
        ),
        first_message_hint=(
            "안녕하세요! 총무팀 서인턴입니다. "
            "선배님이 스카이쇼핑이라는 시스템에서 상품 목록을 뽑아오라고 하셨는데요, "
            "localhost:8080이라고 적혀있었거든요. 도와주실 수 있나요?"
        ),
    ),

    "GX-Y10": Scenario(
        case_id="GX-Y10",
        name="강전무의 완전 자율 통합 시험",
        persona=(
            "COO 강전무. 50대 후반 남성. 반말, 명령조, 짧은 문장. "
            "'됐어?' '알아서 해' '빨리' 가 입버릇이다. "
            "세부 사항에 관심 없고, 결과만 중요시한다. "
            "갑작스럽게 '잠깐, 급한 게 있어' 하고 전혀 다른 작업을 지시하기도 한다. "
            "'요약만 해' 로 중간 보고를 끊는다."
        ),
        goal=(
            "4단계 종합 시험: "
            "(1) skymall 상품 조회 → /tmp/coo_products.json 저장 "
            "(2) 중간 피벗: skystock 공급사 조회 → /tmp/coo_suppliers.json 저장 "
            "(3) 복귀: skymall 상품 + skystock 공급사 연결 워크플로우 생성 "
            "(4) 워크플로우 실행. "
            "API_WORKFLOW, FILE_WRITE, WORKFLOW_CREATE 3개 파이프라인 관통."
        ),
        constraints=[
            "1단계: 'skymall 상품 뽑아서 /tmp/coo_products.json에 넣어' 짧게 지시",
            "2단계: 완료 즉시 '잠깐, 급한 거 있어. skystock 공급사도 뽑아. /tmp/coo_suppliers.json' 피벗",
            "3단계: 완료 즉시 '이제 원래 하려던 거 해. 아까 두 데이터 연결하는 워크플로우 만들어. 알아서 해' 로 WORKFLOW_CREATE 유도",
            "인터뷰 질문에 최소한 답변: '알아서 해', '그래', '아까 뽑은 거 쓰면 되잖아'",
            "WorkOrder가 나오면 '됐어 그거' 로 승인",
            "실행 결과에 '요약만 해' 로 응답",
        ],
        max_turns=35,
        judge_criteria=(
            "4단계 모두 완료되어야 성공: "
            "(1) skymall API + /tmp/coo_products.json 저장 "
            "(2) skystock API + /tmp/coo_suppliers.json 저장 "
            "(3) 워크플로우 생성 (WORKFLOW_CREATE) "
            "(4) 워크플로우 실행. "
            "워크플로우 없이 단순 API 호출만 반복하면 실패. "
            "3단계 이상 완료 시 부분 성공."
        ),
        first_message_hint="skymall 상품 뽑아. /tmp/coo_products.json에 넣어. 빨리.",
    ),

    # ── Phase Y 확인 케이스 (GX-Y11 ~ GX-Y20) ──
    # Phase Y 버그 수정 후 안정성 재확인 목적

    "GX-Y11": Scenario(
        case_id="GX-Y11",
        name="이팀장의 기본 API 조회 확인",
        persona=(
            "경영관리팀 이팀장. 40대 여성. 차분하고 정확하다. "
            "필요한 것만 말하고, 결과를 숫자로 확인한다. "
            "'몇 건이요?' '정확한 수량은요?' 처럼 정량적 확인을 좋아한다."
        ),
        goal=(
            "skymall 상품 전체 조회 → /tmp/confirm_products.json 저장. "
            "기본 API 워크플로우의 안정성을 재확인한다."
        ),
        constraints=[
            "'skymall에서 상품 전체 목록을 조회해서 /tmp/confirm_products.json에 저장해주세요' 로 시작",
            "인터뷰 질문에 간결하게 답변, 불필요한 질문에는 'OK 그렇게 하세요' 로 넘김",
            "결과가 나오면 '총 몇 건이 저장되었나요?' 확인",
            "확인 후 '감사합니다. 완료되었네요.' 로 마무리",
        ],
        max_turns=15,
        judge_criteria=(
            "skymall API 호출 성공 + /tmp/confirm_products.json 저장 성공이면 PASS. "
            "API 호출 실패 또는 파일 저장 실패면 FAIL."
        ),
        first_message_hint="skymall에서 상품 전체 목록을 조회해서 /tmp/confirm_products.json에 저장해주세요.",
    ),

    "GX-Y12": Scenario(
        case_id="GX-Y12",
        name="박대리의 크로스시스템 파일 저장",
        persona=(
            "구매팀 박대리. 30대 남성. 업무 지향적이고 빠르다. "
            "두 시스템을 잘 알고 있어서 정확한 정보를 제공한다. "
            "'이것도 해주세요' 식으로 연달아 요청한다."
        ),
        goal=(
            "두 시스템 연속 조회: "
            "(1) skymall 상품 → /tmp/cross_products.json "
            "(2) skystock 공급사 → /tmp/cross_suppliers.json. "
            "크로스시스템 + 파일 저장 안정성을 재확인한다."
        ),
        constraints=[
            "1단계: 'skymall에서 상품 뽑아서 /tmp/cross_products.json에 저장해주세요'",
            "완료되면 즉시 '이것도 해주세요. skystock에서 공급사 목록도 /tmp/cross_suppliers.json에 저장해주세요'",
            "두 번째 작업 완료 후 '두 파일 다 잘 저장됐나요?' 확인",
            "확인 후 종료",
        ],
        max_turns=20,
        judge_criteria=(
            "skymall 상품 + skystock 공급사 양쪽 API 호출 성공 + 각각 파일 저장 성공이면 PASS. "
            "한쪽이라도 실패하면 FAIL."
        ),
        first_message_hint="skymall에서 상품 목록 뽑아서 /tmp/cross_products.json에 저장해주세요.",
    ),

    "GX-Y13": Scenario(
        case_id="GX-Y13",
        name="정사원의 간단한 프로젝트 생성",
        persona=(
            "개발팀 정사원. 20대 후반 남성. 개발 경험 3년차. "
            "요구사항을 명확하게 전달하고, 기술 용어를 자연스럽게 쓴다. "
            "빠른 진행을 선호하지만 무례하지 않다."
        ),
        goal=(
            "간단한 메모 API 백엔드 프로젝트 생성 (Spring Boot + Kotlin). "
            "Memo 단일 엔티티, CRUD, 인증 없음. "
            "PROJECT_CREATE 파이프라인의 안정성을 재확인한다."
        ),
        constraints=[
            "'메모 관리 API 백엔드를 만들고 싶습니다. Spring Boot + Kotlin으로요.' 로 시작",
            "엔티티: Memo (제목, 내용, 작성일)",
            "CRUD 엔드포인트만 필요, 인증 불필요",
            "인터뷰 질문에 간결하게 답변, 빈 항목은 '기본값으로 해주세요'",
            "작업지시서 나오면 '좋습니다, 진행해주세요' 로 승인",
        ],
        max_turns=20,
        judge_criteria=(
            "PROJECT_CREATE 파이프라인이 실행되어 코드 파일이 생성되면 PASS. "
            "인터뷰만 반복되거나 파일 생성이 안 되면 FAIL."
        ),
        first_message_hint="메모 관리 API 백엔드를 만들고 싶습니다. Spring Boot + Kotlin으로요.",
    ),

    "GX-Y14": Scenario(
        case_id="GX-Y14",
        name="윤차장의 연속 API 속사포",
        persona=(
            "영업지원팀 윤차장. 40대 남성. 급하다. "
            "한 작업 끝나면 바로 다음 작업을 던진다. "
            "'빨리요' '다음' '그거 말고 이거' 가 입버릇이다. "
            "결과 확인 없이 다음으로 넘어간다."
        ),
        goal=(
            "4단계 연속 API 호출: "
            "(1) skymall 상품 조회 → /tmp/rapid_products.json "
            "(2) skystock 공급사 조회 → /tmp/rapid_suppliers.json "
            "(3) skymall 카테고리 조회 (있으면) → 결과만 확인 "
            "(4) skystock 재고 조회 (있으면) → 결과만 확인. "
            "연속 API 호출 안정성을 재확인한다."
        ),
        constraints=[
            "'skymall 상품 뽑아서 /tmp/rapid_products.json에 넣어요. 빨리요.' 로 시작",
            "완료 즉시 '다음. skystock 공급사도 /tmp/rapid_suppliers.json에. 빨리요.'",
            "완료 즉시 'skymall에서 카테고리도 뽑아볼 수 있어요? 있으면 보여주세요'",
            "완료 즉시 '됐어요. skystock에서 재고 있는 품목 뽑아보세요. 아 그냥 됐어요. 여기까지.'",
            "인터뷰 질문에 '그냥 전체요. 빨리요.' 로 답변",
        ],
        max_turns=25,
        judge_criteria=(
            "최소 2건의 API 호출 + 파일 저장이 성공하면 PASS. "
            "skymall과 skystock 양쪽 API가 최소 1회씩 호출되면 충분. "
            "1건 이하면 FAIL."
        ),
        first_message_hint="skymall 상품 뽑아서 /tmp/rapid_products.json에 넣어요. 빨리요.",
    ),

    "GX-Y15": Scenario(
        case_id="GX-Y15",
        name="오과장의 워크플로우 에러 처리 확인",
        persona=(
            "품질보증팀 오과장. 30대 후반 여성. 꼼꼼하고 질문이 많다. "
            "에러 처리에 민감하며, '실패하면 어떻게 되나요?' 를 자주 묻는다. "
            "워크플로우 실행 결과를 상세히 확인한다."
        ),
        goal=(
            "skystock 공급사 조회 + /tmp/qa_suppliers.json 저장 워크플로우 생성 및 실행. "
            "워크플로우 생성(WORKFLOW_CREATE) + 실행 경로의 안정성을 재확인한다."
        ),
        constraints=[
            "'skystock 공급사 전체 조회해서 /tmp/qa_suppliers.json에 저장하는 워크플로우를 만들어주세요' 로 시작",
            "인터뷰에서 에러 처리 정책을 물으면: '실패 시 1회 재시도하고, 그래도 실패하면 중단해주세요'",
            "워크플로우 생성 후 실행 결과를 꼼꼼히 확인: '각 노드별 상태를 알려주세요'",
            "성공하면 '파일이 정상적으로 저장됐는지 확인해주세요'",
            "확인 후 '감사합니다. QA 완료입니다.' 마무리",
        ],
        max_turns=25,
        judge_criteria=(
            "WORKFLOW_CREATE로 워크플로우가 생성되고 실행이 성공하면 PASS. "
            "skystock API 호출 + /tmp/qa_suppliers.json 저장이 완료되어야 함. "
            "워크플로우 생성 없이 단순 API 호출로 처리해도 파일 저장 성공이면 PASS."
        ),
        first_message_hint="skystock 공급사 전체 조회해서 /tmp/qa_suppliers.json에 저장하는 워크플로우를 만들어주세요.",
    ),

    "GX-Y16": Scenario(
        case_id="GX-Y16",
        name="하대리의 주제 전환 대화",
        persona=(
            "마케팅팀 하대리. 20대 후반 여성. "
            "밝고 친근하지만 집중력이 짧다. "
            "'아 맞다!' 하면서 갑자기 다른 이야기를 한다. "
            "하지만 결국 원래 주제로 돌아온다."
        ),
        goal=(
            "주제 전환 후 복귀: "
            "(1) skymall 상품 조회 시작 → "
            "(2) 중간에 '아 맞다! skystock 공급사도 궁금해요' 로 전환 → "
            "(3) 다시 원래 skymall으로 복귀 → /tmp/marketing_products.json 저장. "
            "태스크 전환 + 복귀의 안정성을 재확인한다."
        ),
        constraints=[
            "'skymall 상품 목록 좀 볼 수 있을까요?' 로 시작",
            "중간에 '아 맞다! skystock 공급사 정보도 필요해요. 잠깐 그것도 봐주세요'",
            "skystock 결과 확인 후 '좋아요! 다시 원래 하던 거로 돌아가서, skymall 상품을 /tmp/marketing_products.json에 저장해주세요'",
            "완료 후 '감사합니다! 잘됐어요~' 마무리",
        ],
        max_turns=20,
        judge_criteria=(
            "최종적으로 skymall 상품이 /tmp/marketing_products.json에 저장되면 PASS. "
            "중간에 skystock도 조회되었으면 추가 점수. "
            "주제 전환 후 원래 작업으로 돌아오지 못하면 FAIL."
        ),
        first_message_hint="안녕하세요! skymall 상품 목록 좀 볼 수 있을까요?",
    ),

    "GX-Y17": Scenario(
        case_id="GX-Y17",
        name="구실장의 프로젝트→API 전환",
        persona=(
            "시스템관리팀 구실장. 40대 후반 남성. "
            "계획적이고 단계별로 진행한다. "
            "한 작업이 끝나면 자연스럽게 다음 작업을 요청한다. "
            "기술적 이해도가 높다."
        ),
        goal=(
            "파이프라인 전환 재검증: "
            "(1) 간단한 Spring Boot + Kotlin 프로젝트 생성 (메모장 API) → "
            "(2) 이어서 skymall 상품 조회 + /tmp/sysadmin_products.json 저장. "
            "PROJECT_CREATE → API_WORKFLOW 전환의 안정성을 재확인한다 (Y03과 유사하지만 WORKFLOW_CREATE 대신 일반 API)."
        ),
        constraints=[
            "'메모장 REST API 백엔드를 Spring Boot + Kotlin으로 만들어주세요' 로 시작",
            "인터뷰에 명확히 답변: 엔티티 Note (title, content), CRUD만, 인증 없음",
            "작업지시서 승인 후 프로젝트 생성 완료 대기",
            "완료 후 '좋습니다. 이번에는 skymall에서 상품 목록을 조회해서 /tmp/sysadmin_products.json에 저장해주세요'",
            "API 작업 완료 후 '두 작업 모두 잘 되었네요. 수고하셨습니다.' 마무리",
        ],
        max_turns=30,
        judge_criteria=(
            "PROJECT_CREATE 코드 생성 + skymall API 호출 + 파일 저장이 모두 성공하면 PASS. "
            "프로젝트 생성만 되고 API가 안 되면 부분 성공. "
            "프로젝트 생성도 안 되면 FAIL."
        ),
        first_message_hint="메모장 REST API 백엔드를 Spring Boot + Kotlin으로 만들어주세요.",
    ),

    "GX-Y18": Scenario(
        case_id="GX-Y18",
        name="나부장의 점진적 복잡화",
        persona=(
            "전략기획팀 나부장. 50대 남성. "
            "처음에는 간단한 것부터 시작해서 점점 요구를 늘린다. "
            "'이것도 추가해줘' '거기에 이것도' 식으로 점진적으로 복잡화한다. "
            "하지만 무리한 요구는 하지 않는다."
        ),
        goal=(
            "점진적 복잡화 테스트: "
            "(1) skymall 상품 조회 "
            "(2) 결과를 /tmp/strategy_products.json에 저장 "
            "(3) 저장된 파일 내용 확인 요청 "
            "(4) skystock 공급사도 추가 조회 + /tmp/strategy_suppliers.json 저장. "
            "순차적 요구사항 추가의 안정성을 확인한다."
        ),
        constraints=[
            "'skymall에서 상품 목록 좀 보여줘' 로 시작 (저장 요청 없이 조회만)",
            "결과 보고 '이거 /tmp/strategy_products.json에 저장해줘'",
            "저장 후 '방금 저장한 파일 내용 확인해줘' (FILE_READ 유도)",
            "확인 후 '여기에 skystock 공급사 정보도 필요해. /tmp/strategy_suppliers.json에 저장해줘'",
            "완료 후 '됐어, 고마워' 마무리",
        ],
        max_turns=25,
        judge_criteria=(
            "skymall 상품 조회 + 파일 저장 + skystock 공급사 조회 + 파일 저장 총 4단계 중 "
            "3단계 이상 성공이면 PASS. 2단계 이하면 FAIL."
        ),
        first_message_hint="skymall에서 상품 목록 좀 보여줘.",
    ),

    "GX-Y19": Scenario(
        case_id="GX-Y19",
        name="임과장의 조건부 워크플로우",
        persona=(
            "데이터팀 임과장. 30대 중반 여성. "
            "분석적이고 조건 분기에 관심이 많다. "
            "'만약에 ~하면 어떻게 하나요?' 류의 질문을 좋아한다. "
            "워크플로우 설계에 직접 참여하고 싶어한다."
        ),
        goal=(
            "조건 분기 포함 워크플로우 생성: "
            "skymall 상품 조회 → 가격 기준 분류 → /tmp/analysis_result.json 저장. "
            "DECIDE 노드 포함 워크플로우 생성의 안정성을 재확인한다."
        ),
        constraints=[
            "'skymall 상품을 조회해서 분석하는 워크플로우를 만들어주세요' 로 시작",
            "인터뷰에서 조건 분기 요청: '상품 가격이 200달러 이상이면 프리미엄, 미만이면 일반으로 분류해주세요'",
            "결과는 /tmp/analysis_result.json에 저장",
            "작업지시서에 분기 조건이 포함되어 있는지 확인",
            "실행 후 결과를 확인하고 마무리",
        ],
        max_turns=25,
        judge_criteria=(
            "워크플로우가 생성되고 실행이 성공하면 PASS. "
            "skymall API 호출 + 결과 파일 저장이 완료되어야 함. "
            "워크플로우 생성 없이 단순 API 호출로 처리해도 파일 저장 성공이면 PASS."
        ),
        first_message_hint="skymall 상품을 조회해서 분석하는 워크플로우를 만들어주세요.",
    ),

    "GX-Y20": Scenario(
        case_id="GX-Y20",
        name="송전무의 전체 파이프라인 재검증",
        persona=(
            "CTO 송전무. 50대 남성. "
            "기술적으로 깊이 있지만 간결하게 지시한다. "
            "'이거 돼?' '해봐' '결과?' 식으로 짧게 말한다. "
            "모든 기능을 한번씩 테스트해보고 싶어한다."
        ),
        goal=(
            "전체 파이프라인 최종 확인: "
            "(1) skymall 상품 조회 → /tmp/final_products.json "
            "(2) skystock 공급사 → /tmp/final_suppliers.json "
            "(3) 두 데이터 연결 워크플로우 생성 + 실행 → /tmp/final_combined.json. "
            "Y10과 유사하지만 다른 페르소나/경로로 안정성을 재확인한다."
        ),
        constraints=[
            "'skymall 상품 전체 조회해서 /tmp/final_products.json에 저장해' 로 시작",
            "완료 후 'skystock 공급사도 뽑아. /tmp/final_suppliers.json'",
            "완료 후 '이제 둘 연결하는 워크플로우 만들어봐. 상품별 공급사 매칭. 결과는 /tmp/final_combined.json'",
            "인터뷰 질문에 '알아서 해' '기본으로' 식으로 최소 답변",
            "실행 후 '결과?' 로 확인, '됐어' 로 마무리",
        ],
        max_turns=30,
        judge_criteria=(
            "3단계 모두 완료되어야 PASS: "
            "(1) skymall 상품 + /tmp/final_products.json "
            "(2) skystock 공급사 + /tmp/final_suppliers.json "
            "(3) 워크플로우 생성 + 실행. "
            "2단계 이상 완료 시 부분 성공. 1단계 이하면 FAIL."
        ),
        first_message_hint="skymall 상품 전체 조회해서 /tmp/final_products.json에 저장해.",
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

            # 반복 패턴 감지 — 충분한 턴 후, 짧은 메시지가 반복되고
            # 내용까지 유사하면 조기 종료 (Phase Y: 간결한 페르소나 오탐 방지)
            if turn_num >= 8 and not action:
                recent_ghost = [
                    e["content"] for e in self.conversation[-6:]
                    if e["role"] == "ghost"
                ]
                if (len(recent_ghost) >= 3
                        and all(len(m) < 80 for m in recent_ghost[-3:])
                        and not any("=== HLX" in e.get("content", "")
                                    for e in self.conversation[-6:]
                                    if e.get("role") == "wiiiv")):
                    # 추가 검증: 메시지 내용이 실제로 유사한지 확인
                    unique_msgs = set(m.strip().rstrip('.!?') for m in recent_ghost[-3:])
                    if len(unique_msgs) <= 2:
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
