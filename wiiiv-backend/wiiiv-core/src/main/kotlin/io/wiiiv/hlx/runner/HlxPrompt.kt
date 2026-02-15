package io.wiiiv.hlx.runner

import io.wiiiv.hlx.model.HlxContext
import io.wiiiv.hlx.model.HlxNode
import kotlinx.serialization.json.JsonElement

/**
 * HLX Prompt - LLM 프롬프트 템플릿
 *
 * 노드 타입별 구조화된 프롬프트를 생성한다.
 * LLM 응답은 항상 JSON 형식을 요구한다.
 */
object HlxPrompt {

    private const val ROLE = "You are an HLX workflow executor. Execute the given node and return the result."

    /**
     * Observe 노드 프롬프트 생성
     */
    fun observe(node: HlxNode.Observe, context: HlxContext): String {
        return buildString {
            appendLine(ROLE)
            appendLine()
            appendLine("## Node")
            appendLine("- Type: observe")
            appendLine("- ID: ${node.id}")
            appendLine("- Description: ${node.description}")
            node.target?.let { appendLine("- Target: $it") }
            appendLine()
            appendContextVariables(this, node.input, context)
            appendLine("## Response Format")
            appendLine("Return ONLY valid JSON in this format:")
            appendLine("""{ "result": <observed data> }""")
        }
    }

    /**
     * Transform 노드 프롬프트 생성
     */
    fun transform(node: HlxNode.Transform, context: HlxContext): String {
        return buildString {
            appendLine(ROLE)
            appendLine()
            appendLine("## Node")
            appendLine("- Type: transform")
            appendLine("- ID: ${node.id}")
            appendLine("- Description: ${node.description}")
            node.hint?.let { appendLine("- Hint: ${it.name.lowercase()}") }
            appendLine()
            appendContextVariables(this, node.input, context)
            appendLine("## Response Format")
            appendLine("Return ONLY valid JSON in this format:")
            appendLine("""{ "result": <transformed data> }""")
        }
    }

    /**
     * Decide 노드 프롬프트 생성
     */
    fun decide(node: HlxNode.Decide, context: HlxContext): String {
        return buildString {
            appendLine(ROLE)
            appendLine()
            appendLine("## Node")
            appendLine("- Type: decide")
            appendLine("- ID: ${node.id}")
            appendLine("- Description: ${node.description}")
            appendLine()
            appendLine("## Available Branches")
            node.branches.forEach { (key, target) ->
                appendLine("- \"$key\" -> $target")
            }
            appendLine()
            appendContextVariables(this, node.input, context)
            appendLine("## Response Format")
            appendLine("Return ONLY valid JSON in this format:")
            appendLine("""{ "branch": "<one of the branch keys above>", "reasoning": "<brief explanation>" }""")
        }
    }

    /**
     * Act 노드 프롬프트 생성 (LLM-only 경로)
     */
    fun act(node: HlxNode.Act, context: HlxContext): String {
        return buildString {
            appendLine(ROLE)
            appendLine()
            appendLine("## Node")
            appendLine("- Type: act")
            appendLine("- ID: ${node.id}")
            appendLine("- Description: ${node.description}")
            node.target?.let { appendLine("- Target: $it") }
            appendLine()
            appendContextVariables(this, node.input, context)
            appendLine("## Response Format")
            appendLine("Return ONLY valid JSON in this format:")
            appendLine("""{ "result": <action result> }""")
        }
    }

    /**
     * Act 노드 실행 프롬프트 생성 (Executor 연동 경로)
     *
     * LLM에게 구조화된 BlueprintStep JSON을 요청한다.
     * Available Action Types 목록을 제공하여 LLM이 적절한 step type을 선택하도록 유도한다.
     */
    fun actExecution(node: HlxNode.Act, context: HlxContext): String {
        return buildString {
            appendLine(ROLE)
            appendLine()
            appendLine("## Node")
            appendLine("- Type: act (execution mode)")
            appendLine("- ID: ${node.id}")
            appendLine("- Description: ${node.description}")
            node.target?.let { appendLine("- Target: $it") }
            appendLine()
            appendContextVariables(this, node.input, context)
            appendLine("## Available Action Types")
            appendLine("- COMMAND: Execute a shell command. Params: command (required), args, workingDir, timeoutMs")
            appendLine("- FILE_READ: Read a file. Params: path (required)")
            appendLine("- FILE_WRITE: Write a file. Params: path (required), content (required)")
            appendLine("- FILE_DELETE: Delete a file. Params: path (required)")
            appendLine("- FILE_MKDIR: Create a directory. Params: path (required)")
            appendLine("- API_CALL: Make an HTTP request. Params: url (required), method (GET/POST/PUT/DELETE/PATCH), body, header:<name>")
            appendLine("- NOOP: Do nothing (test/placeholder). Params: any key-value pairs")
            appendLine()
            appendLine("## Response Format")
            appendLine("Return ONLY valid JSON in this format:")
            appendLine("""{ "step": { "type": "<ACTION_TYPE>", "params": { <key-value pairs> } } }""")
            appendLine()
            appendLine("Example:")
            appendLine("""{ "step": { "type": "COMMAND", "params": { "command": "echo", "args": "hello world" } } }""")
        }
    }

    /**
     * 컨텍스트 변수를 프롬프트에 추가
     */
    private fun appendContextVariables(sb: StringBuilder, inputVar: String?, context: HlxContext) {
        val vars = mutableMapOf<String, JsonElement>()

        // input 변수가 지정된 경우 해당 변수 값 포함
        if (inputVar != null) {
            context.variables[inputVar]?.let { vars[inputVar] = it }
        }

        // iteration 컨텍스트가 있으면 포함
        context.iteration?.let { _ ->
            context.variables.forEach { (k, v) ->
                if (k !in vars) vars[k] = v
            }
        }

        if (vars.isNotEmpty()) {
            sb.appendLine("## Context Variables")
            vars.forEach { (key, value) ->
                sb.appendLine("- $key: $value")
            }
            sb.appendLine()
        }
    }
}
