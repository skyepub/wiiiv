package io.wiiiv.server.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.wiiiv.server.config.UserPrincipal
import io.wiiiv.server.dto.common.ApiError
import io.wiiiv.server.dto.common.ApiResponse
import io.wiiiv.server.dto.decision.*
import io.wiiiv.server.registry.WiiivRegistry
import io.wiiiv.server.registry.DecisionRecord
import io.wiiiv.server.registry.SpecInput
import io.wiiiv.dacs.*
import io.wiiiv.governor.GovernorRequest
import io.wiiiv.governor.GovernorResult
import io.wiiiv.governor.LlmGovernor
import io.wiiiv.governor.Spec
import java.time.Instant
import java.util.*

/**
 * Decision Routes - Governor에게 판단 요청
 *
 * POST /api/v2/decisions - 새 판단 요청
 * GET  /api/v2/decisions/{id} - 판단 결과 조회
 *
 * Governor는 Service가 아니다. Authority다.
 */
fun Route.decisionRoutes() {
    route("/decisions") {
        authenticate("auth-jwt") {
            // Request a new decision from Governor
            post {
                val principal = call.principal<UserPrincipal>()!!
                val request = call.receive<DecisionRequest>()

                val decisionId = UUID.randomUUID().toString()

                // 1. Spec 생성 (하드코딩 제거 — LlmGovernor가 intent에서 보강)
                val spec = Spec(
                    id = "spec-${UUID.randomUUID().toString().take(8)}",
                    name = request.spec.intent.take(50),
                    description = request.spec.intent,
                    intent = request.spec.intent,
                    constraints = request.spec.constraints?.associateWith { "required" } ?: emptyMap()
                )

                // 2. GovernorRequest 생성 (type/targetPath는 LlmGovernor가 intent에서 추론)
                val govRequest = GovernorRequest(
                    requestId = decisionId,
                    intent = request.spec.intent
                )

                // 3. Governor가 DACS 포함 단일 판단 (이중 호출 제거)
                val governor = WiiivRegistry.governor
                val (govResult, dacsResult) = if (governor is LlmGovernor) {
                    governor.createBlueprintWithDacsResult(govRequest, spec)
                } else {
                    // Fallback: 기존 Governor 사용 시 DACS 직접 호출
                    val dr = WiiivRegistry.dacs.evaluate(
                        DACSRequest(requestId = decisionId, spec = spec, context = request.context?.sessionId)
                    )
                    governor.createBlueprint(govRequest, spec) to dr
                }

                // DACS 결과가 없는 경우 기본값 생성 (Governor 실패 등)
                val effectiveDacsResult = dacsResult ?: DACSResult(
                    requestId = decisionId,
                    consensus = Consensus.REVISION,
                    reason = "Governor failed before DACS evaluation"
                )

                // 4. 결과 분기
                val (status, blueprintId, requiresApproval) = when (govResult) {
                    is GovernorResult.BlueprintCreated -> {
                        WiiivRegistry.storeBlueprint(govResult.blueprint)
                        Triple(DecisionStatus.APPROVED, govResult.blueprint.id, false)
                    }
                    is GovernorResult.Denied -> {
                        Triple(DecisionStatus.REJECTED, null, false)
                    }
                    is GovernorResult.Failed -> {
                        if (govResult.error.startsWith("REVISION")) {
                            Triple(DecisionStatus.NEEDS_REVISION, null, true)
                        } else {
                            Triple(DecisionStatus.REJECTED, null, false)
                        }
                    }
                }

                // 5. Decision 저장
                val record = DecisionRecord(
                    decisionId = decisionId,
                    specInput = SpecInput(
                        intent = request.spec.intent,
                        constraints = request.spec.constraints
                    ),
                    dacsResult = effectiveDacsResult,
                    blueprintId = blueprintId,
                    status = status.name,
                    createdAt = Instant.now().toString()
                )
                WiiivRegistry.storeDecision(record)

                // 6. 응답
                val votes = effectiveDacsResult.personaOpinions.map { opinion ->
                    PersonaVote(
                        persona = opinion.persona.name.lowercase(),
                        vote = opinion.vote.name,
                        reason = opinion.summary
                    )
                }

                call.respond(
                    HttpStatusCode.Created,
                    ApiResponse.success(
                        DecisionResponse(
                            decisionId = decisionId,
                            status = status,
                            consensus = ConsensusResult(
                                outcome = effectiveDacsResult.consensus.name,
                                votes = votes,
                                rationale = effectiveDacsResult.reason
                            ),
                            blueprintId = blueprintId,
                            requiresApproval = requiresApproval,
                            message = when (status) {
                                DecisionStatus.APPROVED -> "Decision approved. Blueprint ready for execution."
                                DecisionStatus.NEEDS_REVISION -> "Additional information required: ${effectiveDacsResult.reason}"
                                DecisionStatus.REJECTED -> "Decision rejected: ${effectiveDacsResult.reason}"
                                else -> null
                            }
                        )
                    )
                )
            }

            // Get decision by ID
            get("/{id}") {
                val id = call.parameters["id"]
                    ?: throw IllegalArgumentException("Decision ID required")

                val record = WiiivRegistry.getDecision(id)
                    ?: throw NoSuchElementException("Decision not found: $id")

                val votes = record.dacsResult.personaOpinions.map { opinion ->
                    PersonaVote(
                        persona = opinion.persona.name.lowercase(),
                        vote = opinion.vote.name,
                        reason = opinion.summary
                    )
                }

                call.respond(
                    ApiResponse.success(
                        DecisionResponse(
                            decisionId = record.decisionId,
                            status = DecisionStatus.valueOf(record.status),
                            consensus = ConsensusResult(
                                outcome = record.dacsResult.consensus.name,
                                votes = votes,
                                rationale = record.dacsResult.reason
                            ),
                            blueprintId = record.blueprintId,
                            requiresApproval = record.status == "NEEDS_REVISION"
                        )
                    )
                )
            }

            // User approval for pending decisions
            post("/{id}/approve") {
                val id = call.parameters["id"]
                    ?: throw IllegalArgumentException("Decision ID required")

                val record = WiiivRegistry.getDecision(id)
                    ?: throw NoSuchElementException("Decision not found: $id")

                // 이미 승인된 경우
                if (record.userApproved) {
                    call.respond(
                        ApiResponse.success(
                            ApprovalResponse(
                                decisionId = id,
                                approved = true,
                                message = "Already approved"
                            )
                        )
                    )
                    return@post
                }

                // 사용자 승인 처리
                WiiivRegistry.updateDecision(id) { old ->
                    old.copy(userApproved = true)
                }

                call.respond(
                    ApiResponse.success(
                        ApprovalResponse(
                            decisionId = id,
                            approved = true,
                            blueprintId = record.blueprintId,
                            message = "Decision approved by user. Ready for execution."
                        )
                    )
                )
            }

            // User rejection
            post("/{id}/reject") {
                val id = call.parameters["id"]
                    ?: throw IllegalArgumentException("Decision ID required")

                WiiivRegistry.getDecision(id)
                    ?: throw NoSuchElementException("Decision not found: $id")

                // 사용자 거부 처리 (userApproved = false 유지)
                WiiivRegistry.updateDecision(id) { old ->
                    old.copy(status = "REJECTED")
                }

                call.respond(
                    ApiResponse.success(
                        RejectionResponse(
                            decisionId = id,
                            rejected = true,
                            message = "Decision rejected by user"
                        )
                    )
                )
            }
        }
    }
}
