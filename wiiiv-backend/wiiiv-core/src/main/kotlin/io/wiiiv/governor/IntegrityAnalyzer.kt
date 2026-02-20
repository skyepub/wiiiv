package io.wiiiv.governor

import org.slf4j.LoggerFactory

/**
 * Post-generation Integrity Gate
 *
 * LLM이 생성한 파일 목록의 정합성을 정적 분석으로 검증/보정한다.
 * Determinism Gate가 상태 전이를 통제하듯, Integrity Gate가 생성물 정합성을 통제한다.
 *
 * 7개 검사를 순차 실행하며, 각 검사는 독립 try/catch로 보호된다.
 * 전체 실패 시 원본 파일을 그대로 반환한다.
 */
object IntegrityAnalyzer {

    private val log = LoggerFactory.getLogger(IntegrityAnalyzer::class.java)

    data class GeneratedFile(val path: String, val content: String)
    data class IntegrityChange(val filePath: String, val checkName: String, val description: String, val autoFixed: Boolean)
    data class IntegrityResult(val files: List<GeneratedFile>, val changes: List<IntegrityChange>, val warnings: List<IntegrityChange>)

    // ── 매핑 테이블 ──

    /** import 패키지 → build.gradle.kts dependency */
    private val IMPORT_TO_DEPENDENCY = mapOf(
        "jakarta.validation" to "org.springframework.boot:spring-boot-starter-validation",
        "jakarta.persistence" to "org.springframework.boot:spring-boot-starter-data-jpa",
        "org.springframework.security" to "org.springframework.boot:spring-boot-starter-security",
        "org.springframework.web" to "org.springframework.boot:spring-boot-starter-web",
        "io.jsonwebtoken" to listOf(
            "io.jsonwebtoken:jjwt-api:0.12.6",
            "io.jsonwebtoken:jjwt-impl:0.12.6",
            "io.jsonwebtoken:jjwt-jackson:0.12.6"
        )
    )

    /** 무조건 제거할 금지 import */
    private val BANNED_IMPORTS = setOf(
        "org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter",
        "org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder"
    )

    /** javax → jakarta 네임스페이스 변환 (Spring Boot 3.x) */
    private val JAVAX_TO_JAKARTA = listOf(
        "javax.persistence" to "jakarta.persistence",
        "javax.validation" to "jakarta.validation",
        "javax.annotation" to "jakarta.annotation",
        "javax.transaction" to "jakarta.transaction",
        "javax.servlet" to "jakarta.servlet",
        "javax.inject" to "jakarta.inject",
        "javax.mail" to "jakarta.mail",
        "javax.websocket" to "jakarta.websocket"
    )

    /** 코드에서 사용되지만 import가 누락되기 쉬운 표준 타입 매핑 */
    private val STANDARD_TYPE_IMPORTS = mapOf(
        "BigDecimal" to "java.math.BigDecimal",
        "LocalDateTime" to "java.time.LocalDateTime",
        "LocalDate" to "java.time.LocalDate",
        "Instant" to "java.time.Instant",
        "UUID" to "java.util.UUID",
        "SessionCreationPolicy" to "org.springframework.security.config.http.SessionCreationPolicy",
        "Customizer" to "org.springframework.security.config.Customizer",
        "UsernamePasswordAuthenticationToken" to "org.springframework.security.authentication.UsernamePasswordAuthenticationToken",
        "SecurityContextHolder" to "org.springframework.security.core.context.SecurityContextHolder",
        "GrantedAuthority" to "org.springframework.security.core.GrantedAuthority",
        "SimpleGrantedAuthority" to "org.springframework.security.core.authority.SimpleGrantedAuthority",
        "OncePerRequestFilter" to "org.springframework.web.filter.OncePerRequestFilter",
        "HttpServletRequest" to "jakarta.servlet.http.HttpServletRequest",
        "HttpServletResponse" to "jakarta.servlet.http.HttpServletResponse",
        "FilterChain" to "jakarta.servlet.FilterChain",
        "BCryptPasswordEncoder" to "org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder",
        "PasswordEncoder" to "org.springframework.security.crypto.password.PasswordEncoder",
        "ResponseEntity" to "org.springframework.http.ResponseEntity",
        "HttpStatus" to "org.springframework.http.HttpStatus",
        "ServletException" to "jakarta.servlet.ServletException",
        "IOException" to "java.io.IOException",
        "AuthenticationManager" to "org.springframework.security.authentication.AuthenticationManager",
        "UserDetails" to "org.springframework.security.core.userdetails.UserDetails",
        "UserDetailsService" to "org.springframework.security.core.userdetails.UserDetailsService",
        // Jakarta Validation 어노테이션
        "NotBlank" to "jakarta.validation.constraints.NotBlank",
        "NotNull" to "jakarta.validation.constraints.NotNull",
        "NotEmpty" to "jakarta.validation.constraints.NotEmpty",
        "Size" to "jakarta.validation.constraints.Size",
        "Min" to "jakarta.validation.constraints.Min",
        "Max" to "jakarta.validation.constraints.Max",
        "Email" to "jakarta.validation.constraints.Email",
        "Valid" to "jakarta.validation.Valid",
        "Column" to "jakarta.persistence.Column",
        "Enumerated" to "jakarta.persistence.Enumerated",
        "EnumType" to "jakarta.persistence.EnumType"
    )

    /** jjwt 0.9.x → 0.12.x API 변경 매핑 */
    private val JJWT_API_MIGRATIONS = listOf(
        "parseClaimsJws" to "parseSignedClaims",
        ".setSubject(" to ".subject(",
        ".setIssuedAt(" to ".issuedAt(",
        ".setExpiration(" to ".expiration(",
        ".signWith(SignatureAlgorithm." to ".signWith(Keys.hmacShaKeyFor(",
        ".getBody()" to ".getPayload()"
    )

    /** Kotlin에서 불필요한 java.util 컬렉션 import (Kotlin이 자체 타입으로 대체) */
    private val KOTLIN_REPLACED_JAVA_IMPORTS = setOf(
        "java.util.List",
        "java.util.Set",
        "java.util.Map",
        "java.util.Collection",
        "java.util.ArrayList",
        "java.util.HashMap",
        "java.util.HashSet",
        "java.util.LinkedList",
        "java.util.LinkedHashMap",
        "java.util.LinkedHashSet",
        "java.util.Iterator",
        "java.util.Iterable"
    )

    /** LLM이 환각하는 존재하지 않는 클래스명 → 올바른 클래스명 매핑 */
    private val HALLUCINATED_CLASS_FIXES = mapOf(
        "WebAuthenticationFilter" to Pair("OncePerRequestFilter", "org.springframework.web.filter.OncePerRequestFilter"),
        "AbstractAuthenticationFilter" to Pair("OncePerRequestFilter", "org.springframework.web.filter.OncePerRequestFilter"),
        "JwtAuthenticationFilter" to null  // 이 이름은 허용 (커스텀 클래스일 수 있음)
    )

    /** jjwt LLM 타이포 → 올바른 메서드명 */
    private val JJWT_METHOD_TYPOS = mapOf(
        "parselseClaimsJws" to "parseSignedClaims",
        "parsedClaimsJws" to "parseSignedClaims",
        "parsClaimsJws" to "parseSignedClaims",
        "parseClaimJws" to "parseSignedClaims",
        "parserClaimsJws" to "parseSignedClaims"
    )

    /** JpaRepository 기본 메서드 (별도 선언 불필요) */
    private val JPA_STANDARD_METHODS = setOf(
        "findAll", "findById", "findAllById",
        "save", "saveAll", "saveAndFlush", "saveAllAndFlush",
        "deleteById", "delete", "deleteAll", "deleteAllById", "deleteAllInBatch",
        "count", "existsById",
        "flush", "getOne", "getById", "getReferenceById"
    )

    /** Artifact-only → group prefix 매핑 (build.gradle.kts 의존성 표기 수정용) */
    private val ARTIFACT_GROUP_PREFIXES = listOf(
        "spring-boot-" to "org.springframework.boot",
        "kotlin-" to "org.jetbrains.kotlin",
        "jjwt-" to "io.jsonwebtoken",
        "jackson-module-" to "com.fasterxml.jackson.module",
        "jackson-datatype-" to "com.fasterxml.jackson.datatype",
    )
    private val ARTIFACT_EXACT_GROUPS = mapOf(
        "h2" to "com.h2database",
        "postgresql" to "org.postgresql",
        "mysql-connector-j" to "com.mysql",
        "flyway-core" to "org.flywaydb",
        "lombok" to "org.projectlombok",
    )

    /** Placeholder 감지 패턴 */
    private val PLACEHOLDER_PATTERNS = listOf(
        Regex("""//\s*(TODO|FIXME|HACK)\b""", RegexOption.IGNORE_CASE),
        Regex("""throw\s+NotImplementedError"""),
        Regex("""TODO\s*\("""),
    )
    /** Placeholder 함수 body 패턴: 빈 함수 또는 주석만 있는 함수 */
    private val EMPTY_BODY_PATTERN = Regex(
        """\{\s*\n\s*(//[^\n]*\n\s*)*\}""",
        RegexOption.MULTILINE
    )

    /** 어노테이션 → 필수 동반 어노테이션 + import */
    private val ANNOTATION_COMPANIONS = mapOf(
        "@EnableWebSecurity" to Pair("@Configuration", "org.springframework.context.annotation.Configuration"),
        "@EnableMethodSecurity" to Pair("@Configuration", "org.springframework.context.annotation.Configuration")
    )

    // ── 메인 진입점 ──

    fun analyze(files: List<GeneratedFile>): IntegrityResult {
        return try {
            val changes = mutableListOf<IntegrityChange>()
            val warnings = mutableListOf<IntegrityChange>()
            var current = files

            // Check 12: 중복 클래스 선언 제거 (멀티턴 생성 시 다른 경로에 같은 클래스 중복)
            current = runCheck("DuplicateClassRemoval", current, changes) { checkDuplicateClasses(it) }

            // Check 1: 의존성 완전성
            current = runCheck("DependencyCompleteness", current, changes) { checkDependencyCompleteness(it) }

            // Check 19: build.gradle.kts 의존성 표기 수정 (그룹 누락 시 자동 추가)
            current = runCheck("InvalidDependencyNotation", current, changes) { checkInvalidDependencyNotation(it) }

            // Check 13: @Entity data class val → var 변환 (JPA requires mutable properties)
            current = runCheck("EntityValToVar", current, changes) { checkEntityValToVar(it) }

            // Check 22: @Entity에 @Id 필드 누락 시 자동 추가
            current = runCheck("EntityMissingId", current, changes) { checkEntityMissingId(it) }

            // Check 20: @Id @GeneratedValue 필드의 잘못된 custom getter 제거
            current = runCheck("MalformedIdGetter", current, changes) { checkMalformedIdGetter(it) }

            // Check 10: 환각 클래스명 치환 (import 정리 전에 실행)
            current = runCheck("HallucinatedClassFix", current, changes) { checkHallucinatedClassNames(it) }

            // Check 6: javax → jakarta 네임스페이스 변환 (Check 2 앞에 실행해야 import 정리가 올바르게 동작)
            current = runCheck("JavaxToJakarta", current, changes) { checkJavaxToJakarta(it) }

            // Check 11: Kotlin에서 불필요한 java.util 컬렉션 import 제거
            current = runCheck("KotlinCollectionImport", current, changes) { checkKotlinJavaCollectionImports(it) }

            // Check 2: Import 정리
            current = runCheck("ImportCleanup", current, changes) { checkImportCleanup(it) }

            // Check 3: 어노테이션 완전성
            current = runCheck("AnnotationCompleteness", current, changes) { checkAnnotationCompleteness(it) }

            // Check 4: 크로스 레퍼런스 무결성
            current = runCheck("CrossReference", current, changes) { checkCrossReference(it) }

            // Check 14: Service가 호출하는데 Repository에 선언 안 된 메서드 자동 추가
            current = runCheck("MissingRepositoryMethod", current, changes) { checkMissingRepositoryMethods(it) }

            // Check 8: 누락된 표준 타입 import 자동 추가
            current = runCheck("MissingStandardImport", current, changes) { checkMissingStandardImports(it) }

            // Check 15: Kotlin에서 잘못된 단일 인용부호(multi-char) → 이중 인용부호 수정
            current = runCheck("SingleQuoteFix", current, changes) { checkKotlinSingleQuoteStrings(it) }

            // Check 16: 깨진 @Value 어노테이션 복구 (JSON 직렬화 시 ${} 유실)
            current = runCheck("BrokenValueAnnotation", current, changes) { checkBrokenValueAnnotations(it) }

            // Check 9: jjwt 0.9.x → 0.12.x API 마이그레이션
            current = runCheck("JjwtApiMigration", current, changes) { checkJjwtApiMigration(it) }

            // Check 21: JwtProvider 필수 메서드 보완 (validateToken, getUsernameFromToken)
            current = runCheck("JwtProviderCompleteness", current, changes) { checkJwtProviderCompleteness(it) }

            // Check 17: Repository ID 타입 ↔ Service 파라미터 타입 정합성
            current = runCheck("RepoIdTypeAlignment", current, changes) { checkRepoIdTypeAlignment(it) }

            // Check 23: Controller↔Service ID 타입 정합성 + Service .copy() @Id 필드명 정합성
            current = runCheck("ControllerServiceIdAlignment", current, changes) { checkControllerServiceIdAlignment(it) }

            // Check 18: Kotlin secondary constructor → primary constructor 변환
            current = runCheck("SecondaryToPrimaryConstructor", current, changes) { checkSecondaryToPrimaryConstructor(it) }

            // Check 5: data.sql ↔ Entity 정합성 (경고만)
            runWarnCheck("DataSqlEntityAlignment", current, warnings) { checkDataSqlEntityAlignment(it) }

            // Check 7: Placeholder 감지 (경고만)
            runWarnCheck("PlaceholderDetection", current, warnings) { checkPlaceholders(it) }

            log.info("[INTEGRITY] Analysis complete: {} auto-fixes, {} warnings", changes.size, warnings.size)
            IntegrityResult(current, changes, warnings)
        } catch (e: Exception) {
            log.error("[INTEGRITY] Analyzer failed, returning original files: {}", e.message, e)
            IntegrityResult(files, emptyList(), emptyList())
        }
    }

    private fun runCheck(
        name: String,
        files: List<GeneratedFile>,
        changes: MutableList<IntegrityChange>,
        check: (List<GeneratedFile>) -> Pair<List<GeneratedFile>, List<IntegrityChange>>
    ): List<GeneratedFile> {
        return try {
            val (updated, newChanges) = check(files)
            changes.addAll(newChanges)
            for (c in newChanges) {
                log.info("[INTEGRITY] {} — {} : {}", c.checkName, c.filePath, c.description)
            }
            updated
        } catch (e: Exception) {
            log.warn("[INTEGRITY] Check '{}' failed, skipping: {}", name, e.message)
            files
        }
    }

    private fun runWarnCheck(
        name: String,
        files: List<GeneratedFile>,
        warnings: MutableList<IntegrityChange>,
        check: (List<GeneratedFile>) -> List<IntegrityChange>
    ) {
        try {
            val newWarnings = check(files)
            warnings.addAll(newWarnings)
            for (w in newWarnings) {
                log.warn("[INTEGRITY] WARN {} — {} : {}", w.checkName, w.filePath, w.description)
            }
        } catch (e: Exception) {
            log.warn("[INTEGRITY] Warn check '{}' failed, skipping: {}", name, e.message)
        }
    }

    // ── Check 1: 의존성 완전성 (build.gradle.kts ↔ import 동기화) ──

    private fun checkDependencyCompleteness(files: List<GeneratedFile>): Pair<List<GeneratedFile>, List<IntegrityChange>> {
        val changes = mutableListOf<IntegrityChange>()
        val gradleFile = files.find { it.path.endsWith("build.gradle.kts") } ?: return files to changes

        // 모든 .kt 파일의 import 수집
        val allImports = files.filter { it.path.endsWith(".kt") }
            .flatMap { f -> f.content.lines().filter { it.trimStart().startsWith("import ") }.map { it.trim().removePrefix("import ").trim() } }
            .toSet()

        // 필요한 dependency 파악
        val neededDeps = mutableSetOf<String>()
        for (imp in allImports) {
            for ((prefix, dep) in IMPORT_TO_DEPENDENCY) {
                if (imp.startsWith(prefix)) {
                    when (dep) {
                        is String -> neededDeps.add(dep)
                        is List<*> -> dep.filterIsInstance<String>().forEach { neededDeps.add(it) }
                    }
                }
            }
        }

        // 이미 있는 dependency 확인
        val gradleContent = gradleFile.content
        val missingDeps = neededDeps.filter { dep ->
            val artifactId = dep.substringAfterLast(":")
            // "starter-validation" or "jjwt-api:0.12.6" 형태 모두 체크
            !gradleContent.contains(artifactId.substringBefore(":"))
        }

        if (missingDeps.isEmpty()) return files to changes

        // build.gradle.kts에 누락 dependency 추가
        val insertionPoint = findDependencyInsertionPoint(gradleContent)
        if (insertionPoint < 0) return files to changes

        val linesToAdd = missingDeps.joinToString("\n") { dep ->
            val type = if (dep.contains("jjwt-impl") || dep.contains("jjwt-jackson")) "runtimeOnly" else "implementation"
            "    $type(\"$dep\")"
        }

        val updatedGradle = StringBuilder(gradleContent)
            .insert(insertionPoint, "\n$linesToAdd")
            .toString()

        for (dep in missingDeps) {
            changes.add(IntegrityChange(gradleFile.path, "DependencyCompleteness", "Added missing dependency: $dep", true))
        }

        val updatedFiles = files.map { if (it.path == gradleFile.path) GeneratedFile(it.path, updatedGradle) else it }
        return updatedFiles to changes
    }

    private fun findDependencyInsertionPoint(content: String): Int {
        // dependencies { 블록 내 마지막 implementation/runtimeOnly/testImplementation 행 끝 찾기
        val depsBlockStart = content.indexOf("dependencies {")
        if (depsBlockStart < 0) return -1

        val depsSection = content.substring(depsBlockStart)
        val depLinePattern = Regex("""^\s+(implementation|runtimeOnly|testImplementation|api|compileOnly)\(""", RegexOption.MULTILINE)
        val matches = depLinePattern.findAll(depsSection).toList()
        if (matches.isEmpty()) return -1

        val lastMatch = matches.last()
        // 해당 행의 끝까지 찾기
        val lineEnd = depsSection.indexOf('\n', lastMatch.range.first)
        return if (lineEnd >= 0) depsBlockStart + lineEnd else -1
    }

    // ── Check 2: Import 정리 ──

    private fun checkImportCleanup(files: List<GeneratedFile>): Pair<List<GeneratedFile>, List<IntegrityChange>> {
        val changes = mutableListOf<IntegrityChange>()
        val updatedFiles = files.map { file ->
            if (!file.path.endsWith(".kt")) return@map file

            val lines = file.content.lines().toMutableList()
            val importLines = lines.mapIndexedNotNull { idx, line ->
                if (line.trimStart().startsWith("import ")) idx to line.trim().removePrefix("import ").trim()
                else null
            }
            if (importLines.isEmpty()) return@map file

            val toRemove = mutableSetOf<Int>()
            val seenImports = mutableSetOf<String>()

            // 파일 body = import 이후 코드
            val lastImportIdx = importLines.last().first
            val bodyText = lines.subList(lastImportIdx + 1, lines.size).joinToString("\n")

            for ((idx, imp) in importLines) {
                // 금지 import 제거
                if (BANNED_IMPORTS.contains(imp)) {
                    toRemove.add(idx)
                    changes.add(IntegrityChange(file.path, "ImportCleanup", "Removed banned import: $imp", true))
                    continue
                }

                // 중복 import 제거
                if (!seenImports.add(imp)) {
                    toRemove.add(idx)
                    changes.add(IntegrityChange(file.path, "ImportCleanup", "Removed duplicate import: $imp", true))
                    continue
                }

                // 미사용 import 제거 (wildcard는 보존)
                if (!imp.endsWith(".*")) {
                    val simpleName = imp.substringAfterLast(".")
                    if (!bodyText.contains(simpleName)) {
                        toRemove.add(idx)
                        changes.add(IntegrityChange(file.path, "ImportCleanup", "Removed unused import: $imp", true))
                    }
                }
            }

            if (toRemove.isEmpty()) return@map file

            val newLines = lines.filterIndexed { idx, _ -> idx !in toRemove }
            GeneratedFile(file.path, newLines.joinToString("\n"))
        }

        return updatedFiles to changes
    }

    // ── Check 3: 어노테이션 완전성 ──

    private fun checkAnnotationCompleteness(files: List<GeneratedFile>): Pair<List<GeneratedFile>, List<IntegrityChange>> {
        val changes = mutableListOf<IntegrityChange>()
        val updatedFiles = files.map { file ->
            if (!file.path.endsWith(".kt")) return@map file

            var content = file.content
            var modified = false

            for ((trigger, companionPair) in ANNOTATION_COMPANIONS) {
                val (companionAnnotation, companionImport) = companionPair
                if (content.contains(trigger) && !content.contains(companionAnnotation)) {
                    // import 추가
                    if (!content.contains("import $companionImport")) {
                        val importInsertPos = content.lastIndexOf("\nimport ")
                        if (importInsertPos >= 0) {
                            val lineEnd = content.indexOf('\n', importInsertPos + 1)
                            if (lineEnd >= 0) {
                                content = content.substring(0, lineEnd) + "\nimport $companionImport" + content.substring(lineEnd)
                            }
                        }
                    }

                    // 어노테이션 추가 (trigger 어노테이션 바로 앞에)
                    content = content.replace(trigger, "$companionAnnotation\n$trigger")
                    modified = true
                    changes.add(IntegrityChange(file.path, "AnnotationCompleteness", "Added $companionAnnotation (required by $trigger)", true))
                }
            }

            if (modified) GeneratedFile(file.path, content) else file
        }

        return updatedFiles to changes
    }

    // ── Check 4: 크로스 레퍼런스 무결성 ──

    private fun checkCrossReference(files: List<GeneratedFile>): Pair<List<GeneratedFile>, List<IntegrityChange>> {
        val changes = mutableListOf<IntegrityChange>()

        // 프로젝트 패키지 루트 추출 (가장 많이 쓰이는 top-level 패키지)
        val packageDeclarations = files.filter { it.path.endsWith(".kt") }
            .mapNotNull { f ->
                f.content.lines().firstOrNull { it.trimStart().startsWith("package ") }
                    ?.trim()?.removePrefix("package ")?.trim()
            }
        if (packageDeclarations.isEmpty()) return files to changes

        val rootPackage = packageDeclarations
            .groupingBy { it.split(".").take(3).joinToString(".") }
            .eachCount()
            .maxByOrNull { it.value }?.key ?: return files to changes

        // 프로젝트 내부 import 수집
        val allInternalImports = files.filter { it.path.endsWith(".kt") }
            .flatMap { f ->
                f.content.lines()
                    .filter { it.trimStart().startsWith("import ") }
                    .map { it.trim().removePrefix("import ").trim() }
                    .filter { it.startsWith(rootPackage) && !it.endsWith(".*") }
            }.toSet()

        // 이미 존재하는 클래스명 수집
        val existingClasses = files.filter { it.path.endsWith(".kt") }
            .flatMap { f ->
                val classPattern = Regex("""(?:class|interface|object|enum class)\s+(\w+)""")
                classPattern.findAll(f.content).map { it.groupValues[1] }
            }.toSet()

        // 누락 클래스 탐지
        val missingImports = allInternalImports.filter { imp ->
            val className = imp.substringAfterLast(".")
            className !in existingClasses
        }

        if (missingImports.isEmpty()) return files to changes

        // 스텁 생성
        val newFiles = mutableListOf<GeneratedFile>()
        for (imp in missingImports) {
            val className = imp.substringAfterLast(".")
            val packageName = imp.substringBeforeLast(".")
            val packagePath = packageName.replace(".", "/")

            // 기본 경로 추정: src/main/kotlin/{package}
            val baseSrcDir = files.firstOrNull { it.path.contains("src/main/kotlin") }?.path
                ?.substringBefore("src/main/kotlin")
                ?: ""
            val filePath = "${baseSrcDir}src/main/kotlin/$packagePath/$className.kt"

            // 이미 파일이 있으면 스킵
            if (files.any { it.path == filePath } || newFiles.any { it.path == filePath }) continue

            val stubContent = generateStub(packageName, className)
            newFiles.add(GeneratedFile(filePath, stubContent))
            changes.add(IntegrityChange(filePath, "CrossReference", "Generated stub for missing class: $className", true))
        }

        return (files + newFiles) to changes
    }

    private fun generateStub(packageName: String, className: String): String {
        val sb = StringBuilder()
        sb.appendLine("package $packageName")
        sb.appendLine()

        when {
            className.endsWith("Repository") -> {
                sb.appendLine("import org.springframework.data.jpa.repository.JpaRepository")
                sb.appendLine("import org.springframework.stereotype.Repository")
                sb.appendLine()
                sb.appendLine("@Repository")
                sb.appendLine("interface $className : JpaRepository<Any, Long>")
            }
            className.endsWith("Service") -> {
                sb.appendLine("import org.springframework.stereotype.Service")
                sb.appendLine()
                sb.appendLine("@Service")
                sb.appendLine("class $className")
            }
            className.endsWith("Controller") -> {
                sb.appendLine("import org.springframework.web.bind.annotation.RestController")
                sb.appendLine()
                sb.appendLine("@RestController")
                sb.appendLine("class $className")
            }
            className.endsWith("Dto") || className.endsWith("Request") || className.endsWith("Response") -> {
                sb.appendLine()
                sb.appendLine("data class $className(val id: Long? = null)")
            }
            className.endsWith("Config") || className.endsWith("Configuration") -> {
                sb.appendLine("import org.springframework.context.annotation.Configuration")
                sb.appendLine()
                sb.appendLine("@Configuration")
                sb.appendLine("class $className")
            }
            else -> {
                sb.appendLine()
                sb.appendLine("class $className")
            }
        }

        return sb.toString()
    }

    // ── Check 5: data.sql ↔ Entity 정합성 (경고만) ──

    private fun checkDataSqlEntityAlignment(files: List<GeneratedFile>): List<IntegrityChange> {
        val warnings = mutableListOf<IntegrityChange>()
        val dataSql = files.find { it.path.endsWith("data.sql") } ?: return warnings

        // INSERT INTO 테이블명 추출
        val insertPattern = Regex("""INSERT\s+INTO\s+(\w+)""", RegexOption.IGNORE_CASE)
        val sqlTables = insertPattern.findAll(dataSql.content).map { it.groupValues[1].lowercase() }.toSet()

        // @Table(name = "xxx") 또는 클래스명 → snake_case 추출
        val entityTables = mutableSetOf<String>()
        for (file in files.filter { it.path.endsWith(".kt") }) {
            val tablePattern = Regex("""@Table\s*\(\s*name\s*=\s*"(\w+)"""")
            for (match in tablePattern.findAll(file.content)) {
                entityTables.add(match.groupValues[1].lowercase())
            }
            // @Entity 클래스명 → snake_case
            val entityClassPattern = Regex("""@Entity[\s\S]*?class\s+(\w+)""")
            for (match in entityClassPattern.findAll(file.content)) {
                entityTables.add(camelToSnake(match.groupValues[1]))
            }
        }

        for (table in sqlTables) {
            if (table !in entityTables) {
                warnings.add(IntegrityChange(dataSql.path, "DataSqlEntityAlignment", "INSERT INTO '$table' has no matching @Entity/@Table", false))
            }
        }

        return warnings
    }

    // ── Check 6: javax → jakarta 네임스페이스 변환 ──

    private fun checkJavaxToJakarta(files: List<GeneratedFile>): Pair<List<GeneratedFile>, List<IntegrityChange>> {
        val changes = mutableListOf<IntegrityChange>()
        val updatedFiles = files.map { file ->
            if (!file.path.endsWith(".kt")) return@map file

            var content = file.content
            var modified = false

            for ((javax, jakarta) in JAVAX_TO_JAKARTA) {
                if (content.contains("import $javax.")) {
                    content = content.replace("import $javax.", "import $jakarta.")
                    modified = true
                    changes.add(IntegrityChange(file.path, "JavaxToJakarta", "Converted $javax → $jakarta", true))
                }
            }

            if (modified) GeneratedFile(file.path, content) else file
        }

        return updatedFiles to changes
    }

    // ── Check 7: Placeholder 감지 (경고만) ──

    private fun checkPlaceholders(files: List<GeneratedFile>): List<IntegrityChange> {
        val warnings = mutableListOf<IntegrityChange>()

        for (file in files.filter { it.path.endsWith(".kt") && it.path.contains("src/main/") }) {
            val lines = file.content.lines()

            // 패턴 매칭: TODO, NotImplementedError 등
            for ((lineNum, line) in lines.withIndex()) {
                for (pattern in PLACEHOLDER_PATTERNS) {
                    if (pattern.containsMatchIn(line)) {
                        warnings.add(IntegrityChange(
                            file.path, "PlaceholderDetection",
                            "Line ${lineNum + 1}: Placeholder code detected — ${line.trim().take(80)}",
                            false
                        ))
                    }
                }
            }

            // 빈 함수 body 감지: fun xxx() { // comment만 }
            val funcPattern = Regex("""fun\s+(\w+)\s*\([^)]*\)[^{]*\{""")
            for (match in funcPattern.findAll(file.content)) {
                val funcName = match.groupValues[1]
                val braceStart = match.range.last
                // 중괄호 이후 내용 추출
                val afterBrace = file.content.substring(braceStart + 1)
                val closingIdx = findMatchingBrace(afterBrace)
                if (closingIdx >= 0) {
                    val body = afterBrace.substring(0, closingIdx).trim()
                    // body가 비어있거나 주석만 있으면 placeholder
                    val bodyWithoutComments = body.lines()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() && !it.startsWith("//") }
                        .joinToString("")
                    if (bodyWithoutComments.isEmpty() && body.isNotEmpty()) {
                        warnings.add(IntegrityChange(
                            file.path, "PlaceholderDetection",
                            "Function '$funcName' has empty/comment-only body",
                            false
                        ))
                    }
                }
            }
        }

        return warnings
    }

    private fun findMatchingBrace(text: String): Int {
        var depth = 0
        for ((i, ch) in text.withIndex()) {
            when (ch) {
                '{' -> depth++
                '}' -> {
                    if (depth == 0) return i
                    depth--
                }
            }
        }
        return -1
    }

    private fun camelToSnake(name: String): String {
        return name.replace(Regex("([a-z])([A-Z])")) { "${it.groupValues[1]}_${it.groupValues[2]}" }.lowercase()
    }

    // ── Check 8: 누락된 표준 타입 import 자동 추가 ──

    private fun checkMissingStandardImports(files: List<GeneratedFile>): Pair<List<GeneratedFile>, List<IntegrityChange>> {
        val changes = mutableListOf<IntegrityChange>()
        val updatedFiles = files.map { file ->
            if (!file.path.endsWith(".kt")) return@map file

            val lines = file.content.lines()
            val existingImports = lines
                .filter { it.trimStart().startsWith("import ") }
                .map { it.trim().removePrefix("import ").trim() }
                .toSet()

            // import 블록 이후의 코드 본문
            val lastImportIdx = lines.indexOfLast { it.trimStart().startsWith("import ") }
            val bodyText = if (lastImportIdx >= 0) {
                lines.subList(lastImportIdx + 1, lines.size).joinToString("\n")
            } else {
                file.content
            }

            val importsToAdd = mutableListOf<String>()
            for ((typeName, importPath) in STANDARD_TYPE_IMPORTS) {
                // 코드 본문에서 사용되는지 확인 (단어 경계)
                val pattern = Regex("\\b${Regex.escape(typeName)}\\b")
                if (pattern.containsMatchIn(bodyText) && importPath !in existingImports) {
                    // wildcard import로 이미 커버되는지 확인
                    val packagePrefix = importPath.substringBeforeLast(".")
                    if ("$packagePrefix.*" !in existingImports) {
                        importsToAdd.add(importPath)
                        changes.add(IntegrityChange(file.path, "MissingStandardImport",
                            "Added missing import: $importPath (for $typeName)", true))
                    }
                }
            }

            if (importsToAdd.isEmpty()) return@map file

            // import 블록 끝에 추가
            val newLines = lines.toMutableList()
            val insertIdx = if (lastImportIdx >= 0) lastImportIdx + 1 else {
                // package 선언 다음 줄에 삽입
                val pkgIdx = newLines.indexOfFirst { it.trimStart().startsWith("package ") }
                if (pkgIdx >= 0) pkgIdx + 1 else 0
            }
            for ((i, imp) in importsToAdd.withIndex()) {
                newLines.add(insertIdx + i, "import $imp")
            }

            GeneratedFile(file.path, newLines.joinToString("\n"))
        }

        return updatedFiles to changes
    }

    // ── Check 9: jjwt 0.9.x → 0.12.x API 마이그레이션 ──

    private fun checkJjwtApiMigration(files: List<GeneratedFile>): Pair<List<GeneratedFile>, List<IntegrityChange>> {
        val changes = mutableListOf<IntegrityChange>()
        val updatedFiles = files.map { file ->
            if (!file.path.endsWith(".kt")) return@map file

            // jjwt 관련 파일만 처리
            if (!file.content.contains("io.jsonwebtoken") && !file.content.contains("Jwts.")) return@map file

            var content = file.content
            var modified = false
            var needsKeysImport = false

            // ── 0. LLM 타이포 수정 (다른 변환보다 선행) ──
            for ((typo, correct) in JJWT_METHOD_TYPOS) {
                if (content.contains(typo)) {
                    content = content.replace(typo, correct)
                    modified = true
                    changes.add(IntegrityChange(file.path, "JjwtApiMigration",
                        "Fixed typo: $typo → $correct", true))
                }
            }

            // ── 1. Jwts.claims() 패턴 → builder 체인 리팩토링 ──
            if (content.contains("Jwts.claims()")) {
                val refactored = refactorJwtClaimsPattern(content, changes, file.path)
                if (refactored != content) {
                    content = refactored
                    modified = true
                }
            }

            // ── 1b. Safety net: val x: Claims = Jwts.claims()... → add .build() ──
            val claimsAssignPattern = Regex("""(val\s+\w+)\s*:\s*Claims\s*=\s*(Jwts\.claims\(\)[^\n]*?)(\s*\n)""")
            if (claimsAssignPattern.containsMatchIn(content)) {
                content = claimsAssignPattern.replace(content) { match ->
                    val varDecl = match.groupValues[1]
                    var expr = match.groupValues[2].trimEnd()
                    val trail = match.groupValues[3]
                    if (!expr.endsWith(".build()")) expr = "$expr.build()"
                    "$varDecl = $expr$trail"
                }
                modified = true
                changes.add(IntegrityChange(file.path, "JjwtApiMigration",
                    "Added .build() to Jwts.claims() assignment and removed Claims type (jjwt 0.12.x)", true))
            }

            // parseClaimsJws → parseSignedClaims
            if (content.contains("parseClaimsJws")) {
                content = content.replace("parseClaimsJws", "parseSignedClaims")
                modified = true
                changes.add(IntegrityChange(file.path, "JjwtApiMigration",
                    "Migrated parseClaimsJws → parseSignedClaims (jjwt 0.12.x)", true))
            }

            // ── 2. Jwts.parseSignedClaims(token) → Jwts.parser().verifyWith(key).build().parseSignedClaims(token) ──
            // parseSignedClaims는 JwtParser의 메서드이지, Jwts의 static 메서드가 아님
            val directParsePattern = Regex("""Jwts\.(parseSignedClaims)\(""")
            if (directParsePattern.containsMatchIn(content)) {
                val keyVarName = findJwtSecretKeyVar(content) ?: "secretKey"
                content = directParsePattern.replace(content) { _ ->
                    "Jwts.parser().verifyWith(Keys.hmacShaKeyFor($keyVarName.toByteArray())).build().parseSignedClaims("
                }
                needsKeysImport = true
                modified = true
                changes.add(IntegrityChange(file.path, "JjwtApiMigration",
                    "Fixed Jwts.parseSignedClaims() → Jwts.parser().verifyWith().build().parseSignedClaims()", true))
            }

            // .getBody() → .getPayload()
            if (content.contains(".getBody()") && content.contains("Jwts.")) {
                content = content.replace(".getBody()", ".getPayload()")
                modified = true
                changes.add(IntegrityChange(file.path, "JjwtApiMigration",
                    "Migrated .getBody() → .getPayload() (jjwt 0.12.x)", true))
            }

            // Jwts.parser().setSigningKey(key) → Jwts.parser().verifyWith(Keys.hmacShaKeyFor(key.toByteArray())).build()
            val setSigningKeyPattern = Regex("""Jwts\.parser\(\)\s*\.setSigningKey\(([^)]+)\)""")
            if (setSigningKeyPattern.containsMatchIn(content)) {
                content = setSigningKeyPattern.replace(content) { match ->
                    val keyExpr = match.groupValues[1].trim()
                    "Jwts.parser().verifyWith(Keys.hmacShaKeyFor($keyExpr.toByteArray())).build()"
                }
                needsKeysImport = true
                modified = true
                changes.add(IntegrityChange(file.path, "JjwtApiMigration",
                    "Migrated setSigningKey → verifyWith(Keys.hmacShaKeyFor()).build() (jjwt 0.12.x)", true))
            }

            // verifyWith(stringKey) → verifyWith(Keys.hmacShaKeyFor(stringKey.toByteArray()))
            // 이미 Keys.hmacShaKeyFor()로 감싸진 경우는 스킵
            val verifyWithStringPattern = Regex("""\.verifyWith\((?!Keys\.hmacShaKeyFor)([^)]+)\)""")
            if (verifyWithStringPattern.containsMatchIn(content)) {
                content = verifyWithStringPattern.replace(content) { match ->
                    val keyExpr = match.groupValues[1].trim()
                    ".verifyWith(Keys.hmacShaKeyFor($keyExpr.toByteArray()))"
                }
                needsKeysImport = true
                modified = true
                changes.add(IntegrityChange(file.path, "JjwtApiMigration",
                    "Wrapped verifyWith() key with Keys.hmacShaKeyFor().toByteArray() (jjwt 0.12.x)", true))
            }

            // signWith(SignatureAlgorithm.XXX, key) → signWith(Keys.hmacShaKeyFor(key.toByteArray()))
            val signWithOldPattern = Regex("""\.signWith\(SignatureAlgorithm\.\w+,\s*([^)]+)\)""")
            if (signWithOldPattern.containsMatchIn(content)) {
                content = signWithOldPattern.replace(content) { match ->
                    val keyExpr = match.groupValues[1].trim()
                    ".signWith(Keys.hmacShaKeyFor($keyExpr.toByteArray()))"
                }
                needsKeysImport = true
                modified = true
                changes.add(IntegrityChange(file.path, "JjwtApiMigration",
                    "Migrated signWith(SignatureAlgorithm, key) → signWith(Keys.hmacShaKeyFor()) (jjwt 0.12.x)", true))
            }

            // ── 3. .body → .payload (프로퍼티 접근 — 줄 끝 포함 모든 위치) ──
            val bodyPropertyPattern = Regex("""\)\.body\b""")
            if (bodyPropertyPattern.containsMatchIn(content)) {
                content = bodyPropertyPattern.replace(content, ").payload")
                modified = true
                changes.add(IntegrityChange(file.path, "JjwtApiMigration",
                    "Migrated .body → .payload (jjwt 0.12.x property access)", true))
            }

            // .setClaims( → .claims(
            if (content.contains(".setClaims(")) {
                content = content.replace(".setClaims(", ".claims(")
                modified = true
                changes.add(IntegrityChange(file.path, "JjwtApiMigration",
                    "Migrated .setClaims( → .claims( (jjwt 0.12.x)", true))
            }

            // Jwts.builder().setSubject/setIssuedAt/setExpiration → subject/issuedAt/expiration
            for ((old, new) in listOf(
                ".setSubject(" to ".subject(",
                ".setIssuedAt(" to ".issuedAt(",
                ".setExpiration(" to ".expiration("
            )) {
                if (content.contains(old)) {
                    content = content.replace(old, new)
                    modified = true
                    changes.add(IntegrityChange(file.path, "JjwtApiMigration",
                        "Migrated $old → $new (jjwt 0.12.x)", true))
                }
            }

            // SignatureAlgorithm import 제거 (더 이상 필요 없음)
            if (content.contains("import io.jsonwebtoken.SignatureAlgorithm")) {
                content = content.replace("import io.jsonwebtoken.SignatureAlgorithm\n", "")
                modified = true
                changes.add(IntegrityChange(file.path, "JjwtApiMigration",
                    "Removed obsolete import: io.jsonwebtoken.SignatureAlgorithm", true))
            }

            // Keys import 추가
            if (needsKeysImport && !content.contains("import io.jsonwebtoken.security.Keys")) {
                val lastImportIdx = content.lastIndexOf("\nimport ")
                if (lastImportIdx >= 0) {
                    val lineEnd = content.indexOf('\n', lastImportIdx + 1)
                    if (lineEnd >= 0) {
                        content = content.substring(0, lineEnd) + "\nimport io.jsonwebtoken.security.Keys" + content.substring(lineEnd)
                    }
                }
                changes.add(IntegrityChange(file.path, "JjwtApiMigration",
                    "Added import: io.jsonwebtoken.security.Keys", true))
            }

            // Claims import 제거 (Jwts.claims() 리팩토링 후 불필요할 수 있음)
            if (modified && !content.contains(": Claims") && !content.contains("<Claims>")) {
                val claimsImportPattern = Regex("""import io\.jsonwebtoken\.Claims\n""")
                if (claimsImportPattern.containsMatchIn(content)) {
                    content = claimsImportPattern.replace(content, "")
                    changes.add(IntegrityChange(file.path, "JjwtApiMigration",
                        "Removed unused import: io.jsonwebtoken.Claims", true))
                }
            }

            if (modified) GeneratedFile(file.path, content) else file
        }

        return updatedFiles to changes
    }

    /** Jwts.claims() 패턴을 builder 체인 방식으로 리팩토링 */
    private fun refactorJwtClaimsPattern(content: String, changes: MutableList<IntegrityChange>, filePath: String): String {
        val lines = content.lines().toMutableList()

        // Jwts.claims() 선언 라인 찾기
        val claimsLineIdx = lines.indexOfFirst { it.contains("Jwts.claims()") }
        if (claimsLineIdx < 0) return content

        val claimsLine = lines[claimsLineIdx]

        // 변수명 추출: val claims[: Type] = Jwts.claims()...
        val varPattern = Regex("""(?:val|var)\s+(\w+)\s*(?::\s*\w+)?\s*=\s*Jwts\.claims\(\)(.*)""")
        val varMatch = varPattern.find(claimsLine) ?: return content
        val varName = varMatch.groupValues[1]
        val chainedCalls = varMatch.groupValues[2].trim()

        // subject 추출
        var subjectExpr: String? = null
        val subjectPattern = Regex("""\.(?:set)?[Ss]ubject\(([^)]+)\)""")
        val subjectMatch = subjectPattern.find(chainedCalls)
        if (subjectMatch != null) {
            subjectExpr = subjectMatch.groupValues[1]
        }

        // claim 할당 수집: claims['key'] = value, claims["key"] = value
        val claimAssignments = mutableListOf<Pair<String, String>>()
        val linesToRemove = mutableSetOf(claimsLineIdx)

        for (i in claimsLineIdx + 1 until lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty()) continue

            val assignPattern = Regex("""${Regex.escape(varName)}\s*\[\s*['"](\w+)['"]\s*]\s*=\s*(.+)""")
            val assignMatch = assignPattern.find(line)
            if (assignMatch != null) {
                claimAssignments.add(assignMatch.groupValues[1] to assignMatch.groupValues[2].trim())
                linesToRemove.add(i)
                continue
            }

            val putPattern = Regex("""${Regex.escape(varName)}\.put\(\s*["'](\w+)["']\s*,\s*(.+)\)""")
            val putMatch = putPattern.find(line)
            if (putMatch != null) {
                claimAssignments.add(putMatch.groupValues[1] to putMatch.groupValues[2].trim())
                linesToRemove.add(i)
                continue
            }

            // claims 변수를 사용하지 않는 라인이면 중단
            if (!line.startsWith(varName) && !line.startsWith("//")) break
        }

        // builder 체인 구성
        val newChainParts = mutableListOf<String>()
        if (subjectExpr != null) newChainParts.add(".subject($subjectExpr)")
        for ((key, value) in claimAssignments) {
            newChainParts.add(".claim(\"$key\", $value)")
        }

        if (newChainParts.isEmpty()) return content

        // .claims(varName) 또는 .setClaims(varName) 을 새 체인으로 교체
        val builderClaimsPattern = Regex("""\.(?:set)?[Cc]laims\(${Regex.escape(varName)}\)""")
        val builderClaimsIdx = lines.indexOfFirst { builderClaimsPattern.containsMatchIn(it) }
        if (builderClaimsIdx >= 0) {
            val indent = lines[builderClaimsIdx].takeWhile { it == ' ' || it == '\t' }
            lines[builderClaimsIdx] = newChainParts.joinToString("\n") { "$indent$it" }
        }

        // claims 변수 선언 및 할당 라인 제거 (역순)
        for (idx in linesToRemove.sortedDescending()) {
            lines.removeAt(idx)
        }

        changes.add(IntegrityChange(filePath, "JjwtApiMigration",
            "Refactored Jwts.claims() → builder-chain style (.subject/.claim) (jjwt 0.12.x)", true))

        return lines.joinToString("\n")
    }

    /** JWT 시크릿 키 변수명 찾기 */
    private fun findJwtSecretKeyVar(content: String): String? {
        val patterns = listOf(
            Regex("""(?:private\s+)?(?:val|var)\s+(secretKey|secret|jwtSecret|jwtSecretKey|SECRET_KEY|key)\b""", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            val match = pattern.find(content)
            if (match != null) return match.groupValues[1]
        }
        val valuePattern = Regex("""@Value[^)]*\)\s*(?:private\s+)?(?:val|var)\s+(\w*(?:[Ss]ecret|[Kk]ey)\w*)\b""")
        val valueMatch = valuePattern.find(content)
        if (valueMatch != null) return valueMatch.groupValues[1]
        return null
    }

    // ── Check 21: JwtProvider 필수 메서드 보완 ──

    private fun checkJwtProviderCompleteness(files: List<GeneratedFile>): Pair<List<GeneratedFile>, List<IntegrityChange>> {
        val changes = mutableListOf<IntegrityChange>()

        // JwtProvider 파일 찾기
        val jwtProviderFile = files.find {
            it.path.endsWith("JwtProvider.kt") && it.content.contains("generateToken")
        } ?: return files to changes

        // JwtAuthFilter에서 호출하는 메서드 확인
        val jwtFilterFile = files.find {
            (it.path.contains("JwtAuth") || it.path.contains("JwtFilter")) && it.path.endsWith(".kt")
        }

        val missingMethods = mutableListOf<String>()
        if (jwtFilterFile != null) {
            if (jwtFilterFile.content.contains("validateToken") && !jwtProviderFile.content.contains("fun validateToken"))
                missingMethods.add("validateToken")
            if (jwtFilterFile.content.contains("getUsernameFromToken") && !jwtProviderFile.content.contains("fun getUsernameFromToken"))
                missingMethods.add("getUsernameFromToken")
            if (jwtFilterFile.content.contains("getRoleFromToken") && !jwtProviderFile.content.contains("fun getRoleFromToken"))
                missingMethods.add("getRoleFromToken")
        }

        if (missingMethods.isEmpty()) return files to changes

        val secretKeyVar = findJwtSecretKeyVar(jwtProviderFile.content) ?: "secretKey"
        val methodsToAdd = StringBuilder()

        // getClaimsFromToken 헬퍼 추가 (이미 없다면)
        val hasClaimsHelper = jwtProviderFile.content.contains("fun getClaimsFromToken") ||
                jwtProviderFile.content.contains("fun getClaims")
        if (!hasClaimsHelper) {
            methodsToAdd.append("""

    private fun getClaimsFromToken(token: String): io.jsonwebtoken.Claims {
        val cleanToken = if (token.startsWith("Bearer ")) token.substring(7) else token
        return io.jsonwebtoken.Jwts.parser()
            .verifyWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor($secretKeyVar.toByteArray()))
            .build()
            .parseSignedClaims(cleanToken)
            .payload
    }""")
        }

        if ("validateToken" in missingMethods) {
            methodsToAdd.append("""

    fun validateToken(token: String): Boolean {
        return try {
            getClaimsFromToken(token)
            true
        } catch (e: Exception) {
            false
        }
    }""")
        }

        if ("getUsernameFromToken" in missingMethods) {
            methodsToAdd.append("""

    fun getUsernameFromToken(token: String): String {
        return getClaimsFromToken(token).subject
    }""")
        }

        if ("getRoleFromToken" in missingMethods) {
            methodsToAdd.append("""

    fun getRoleFromToken(token: String): String? {
        return getClaimsFromToken(token)["role"] as? String
    }""")
        }

        // 클래스의 마지막 닫는 중괄호 앞에 삽입
        val lastBrace = jwtProviderFile.content.lastIndexOf('}')
        if (lastBrace < 0) return files to changes

        val newContent = jwtProviderFile.content.substring(0, lastBrace) +
                methodsToAdd.toString() + "\n" +
                jwtProviderFile.content.substring(lastBrace)

        val updatedFiles = files.map {
            if (it.path == jwtProviderFile.path) GeneratedFile(it.path, newContent) else it
        }
        changes.add(IntegrityChange(jwtProviderFile.path, "JwtProviderCompleteness",
            "Added missing JWT methods: ${missingMethods.joinToString(", ")}", true))

        return updatedFiles to changes
    }

    // ── Check 22: @Entity에 @Id 필드 누락 시 자동 추가 ──

    private fun checkEntityMissingId(files: List<GeneratedFile>): Pair<List<GeneratedFile>, List<IntegrityChange>> {
        val changes = mutableListOf<IntegrityChange>()
        val updatedFiles = files.map { file ->
            if (!file.path.endsWith(".kt")) return@map file
            if (!file.content.contains("@Entity")) return@map file
            if (file.content.contains("@Id")) return@map file  // 이미 @Id가 있음

            // data class 생성자의 첫 번째 파라미터 앞에 @Id 필드 삽입
            val dataClassPattern = Regex("""(data\s+class\s+\w+\s*\()""")
            val match = dataClassPattern.find(file.content) ?: return@map file

            val insertPos = match.range.last + 1
            val indent = "    "
            val idField = "\n${indent}@Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,\n${indent}"

            val newContent = file.content.substring(0, insertPos) + idField + file.content.substring(insertPos).trimStart()

            changes.add(IntegrityChange(file.path, "EntityMissingId",
                "Added @Id @GeneratedValue field to @Entity data class", true))
            GeneratedFile(file.path, newContent)
        }
        return updatedFiles to changes
    }

    // ── Check 23: Controller↔Service ID 타입 정합성 + .copy() @Id 필드명 정합성 ──

    private fun checkControllerServiceIdAlignment(files: List<GeneratedFile>): Pair<List<GeneratedFile>, List<IntegrityChange>> {
        val changes = mutableListOf<IntegrityChange>()

        // Entity별 @Id 필드명과 타입 수집
        data class IdInfo(val fieldName: String, val fieldType: String, val entityName: String)
        val entityIdMap = mutableMapOf<String, IdInfo>() // entityName → IdInfo

        for (file in files) {
            if (!file.path.endsWith(".kt") || !file.content.contains("@Entity")) continue
            val entityNameMatch = Regex("""(?:data\s+)?class\s+(\w+)""").find(file.content) ?: continue
            val entityName = entityNameMatch.groupValues[1]

            // @Id가 있는 필드 찾기
            val lines = file.content.lines()
            for (i in lines.indices) {
                if (!lines[i].trim().contains("@Id")) continue
                // @Id와 같은 줄 또는 바로 아래 줄에서 var/val 필드명 추출
                for (j in i until minOf(i + 4, lines.size)) {
                    val fieldMatch = Regex("""(?:var|val)\s+(\w+)\s*:\s*(\w+)""").find(lines[j])
                    if (fieldMatch != null) {
                        entityIdMap[entityName] = IdInfo(fieldMatch.groupValues[1], fieldMatch.groupValues[2], entityName)
                        break
                    }
                }
                break
            }
        }

        if (entityIdMap.isEmpty()) return files to changes

        var updatedFiles = files

        // 1. Service의 .copy(id = value) → .copy(actualFieldName = value) 수정
        updatedFiles = updatedFiles.map { file ->
            if (!file.path.endsWith("Service.kt")) return@map file
            var content = file.content
            var modified = false

            for ((entityName, idInfo) in entityIdMap) {
                if (idInfo.fieldName == "id") continue  // id면 수정 불필요
                if (!content.contains(entityName)) continue

                // .copy(id = value) → .copy(actualFieldName = value)
                val copyPattern = Regex("""\\.copy\\(id\\s*=\\s*""")
                if (copyPattern.containsMatchIn(content)) {
                    content = copyPattern.replace(content) { ".copy(${idInfo.fieldName} = " }
                    modified = true
                    changes.add(IntegrityChange(file.path, "ControllerServiceIdAlignment",
                        "Fixed .copy(id=) → .copy(${idInfo.fieldName}=) for $entityName", true))
                }
            }

            if (modified) GeneratedFile(file.path, content) else file
        }

        // 2. Controller @PathVariable id 타입 수정 (Service id 타입과 일치시킴)
        updatedFiles = updatedFiles.map { file ->
            if (!file.path.endsWith("Controller.kt")) return@map file
            var content = file.content
            var modified = false

            // Controller가 어떤 Service를 사용하는지 찾기
            val serviceFieldPattern = Regex("""private\s+val\s+\w+:\s+(\w+)Service""")
            val serviceMatch = serviceFieldPattern.find(content)
            val entityPrefix = serviceMatch?.groupValues?.get(1) ?: return@map file

            // 해당 Entity의 ID 타입 확인
            val idInfo = entityIdMap[entityPrefix] ?: return@map file
            val expectedType = idInfo.fieldType

            // @PathVariable id: <WrongType> → @PathVariable id: <CorrectType>
            val pvPattern = Regex("""@PathVariable\s+id\s*:\s*(\w+)""")
            for (pvMatch in pvPattern.findAll(content)) {
                val currentType = pvMatch.groupValues[1]
                if (currentType != expectedType) {
                    content = content.replace(pvMatch.value, "@PathVariable id: $expectedType")
                    modified = true
                    changes.add(IntegrityChange(file.path, "ControllerServiceIdAlignment",
                        "Changed @PathVariable id: $currentType → $expectedType (matching $entityPrefix entity)", true))
                }
            }

            if (modified) GeneratedFile(file.path, content) else file
        }

        return updatedFiles to changes
    }

    // ── Check 10: 환각 클래스명 치환 ──

    private fun checkHallucinatedClassNames(files: List<GeneratedFile>): Pair<List<GeneratedFile>, List<IntegrityChange>> {
        val changes = mutableListOf<IntegrityChange>()
        val updatedFiles = files.map { file ->
            if (!file.path.endsWith(".kt")) return@map file

            var content = file.content
            var modified = false

            for ((wrongName, fix) in HALLUCINATED_CLASS_FIXES) {
                if (fix == null) continue  // null = 허용된 이름
                val (correctName, correctImport) = fix

                if (content.contains(wrongName)) {
                    // ★ import 라인을 클래스명 치환 BEFORE에 찾기 (치환 후에는 wrongName이 사라짐)
                    val lines = content.lines().toMutableList()
                    val wrongImportIdx = lines.indexOfFirst { it.contains("import ") && it.contains(wrongName) }

                    // 클래스 참조 치환 (import 포함 전체)
                    content = content.replace(wrongName, correctName)
                    modified = true
                    changes.add(IntegrityChange(file.path, "HallucinatedClassFix",
                        "Replaced hallucinated '$wrongName' → '$correctName'", true))

                    // import 수정: 잘못된 패키지 경로의 import를 올바른 것으로 교체
                    val updatedLines = content.lines().toMutableList()
                    if (wrongImportIdx >= 0) {
                        // 원래 잘못된 import가 있던 자리에 올바른 import로 교체
                        updatedLines[wrongImportIdx] = "import $correctImport"
                        // 이미 올바른 import가 다른 곳에도 있으면 중복 제거
                        val duplicates = updatedLines.indices.filter { idx ->
                            idx != wrongImportIdx && updatedLines[idx].trim() == "import $correctImport"
                        }
                        for (idx in duplicates.reversed()) {
                            updatedLines.removeAt(idx)
                        }
                    } else if (!content.contains("import $correctImport")) {
                        // import가 아예 없으면 추가
                        val lastImportIdx = updatedLines.indexOfLast { it.trimStart().startsWith("import ") }
                        if (lastImportIdx >= 0) {
                            updatedLines.add(lastImportIdx + 1, "import $correctImport")
                        }
                    }
                    content = updatedLines.joinToString("\n")
                }
            }

            if (modified) GeneratedFile(file.path, content) else file
        }

        return updatedFiles to changes
    }

    // ── Check 11: Kotlin에서 불필요한 java.util 컬렉션 import 제거 ──

    private fun checkKotlinJavaCollectionImports(files: List<GeneratedFile>): Pair<List<GeneratedFile>, List<IntegrityChange>> {
        val changes = mutableListOf<IntegrityChange>()
        val updatedFiles = files.map { file ->
            if (!file.path.endsWith(".kt")) return@map file

            val lines = file.content.lines().toMutableList()
            val toRemove = mutableSetOf<Int>()

            for ((idx, line) in lines.withIndex()) {
                if (!line.trimStart().startsWith("import ")) continue
                val imp = line.trim().removePrefix("import ").trim()
                if (imp in KOTLIN_REPLACED_JAVA_IMPORTS) {
                    toRemove.add(idx)
                    changes.add(IntegrityChange(file.path, "KotlinCollectionImport",
                        "Removed Java collection import unnecessary in Kotlin: $imp", true))
                }
            }

            if (toRemove.isEmpty()) return@map file
            val newLines = lines.filterIndexed { idx, _ -> idx !in toRemove }
            GeneratedFile(file.path, newLines.joinToString("\n"))
        }

        return updatedFiles to changes
    }

    // ── Check 13: @Entity data class val → var 변환 ──

    private fun checkEntityValToVar(files: List<GeneratedFile>): Pair<List<GeneratedFile>, List<IntegrityChange>> {
        val changes = mutableListOf<IntegrityChange>()
        val updatedFiles = files.map { file ->
            if (!file.path.endsWith(".kt")) return@map file
            if (!file.content.contains("@Entity")) return@map file

            var content = file.content

            // @Entity 클래스의 생성자 찾기
            val entityClassRegex = Regex("""@Entity[\s\S]*?(?:data\s+)?class\s+\w+\s*\(""")
            val entityMatch = entityClassRegex.find(content) ?: return@map file

            val constructorStart = entityMatch.range.last // '(' 위치
            var depth = 1
            var constructorEnd = constructorStart + 1
            for (i in constructorStart + 1 until content.length) {
                when (content[i]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) { constructorEnd = i; break }
                    }
                }
            }

            val constructorContent = content.substring(constructorStart + 1, constructorEnd)
            if (!constructorContent.contains(Regex("""\bval\b"""))) return@map file

            val newConstructor = constructorContent.replace(Regex("""\bval\b"""), "var")
            val newContent = content.substring(0, constructorStart + 1) + newConstructor + content.substring(constructorEnd)

            changes.add(IntegrityChange(file.path, "EntityValToVar",
                "Changed val → var in @Entity constructor (JPA requires mutable properties)", true))
            GeneratedFile(file.path, newContent)
        }
        return updatedFiles to changes
    }

    // ── Check 14: Service가 호출하는 Repository 메서드 자동 추가 ──

    private fun checkMissingRepositoryMethods(files: List<GeneratedFile>): Pair<List<GeneratedFile>, List<IntegrityChange>> {
        val changes = mutableListOf<IntegrityChange>()

        // 1. Repository 인터페이스 파싱
        data class RepoInfo(var file: GeneratedFile, val entityType: String, val methods: MutableSet<String>)
        val repos = mutableMapOf<String, RepoInfo>()

        for (file in files.filter { it.path.endsWith(".kt") }) {
            val repoPattern = Regex("""interface\s+(\w+Repository)\s*:\s*JpaRepository<(\w+),\s*(\w+\??)>""")
            val match = repoPattern.find(file.content) ?: continue
            val repoName = match.groupValues[1]
            val entityType = match.groupValues[2]
            val methodPattern = Regex("""fun\s+(\w+)\(""")
            val methods = methodPattern.findAll(file.content).map { it.groupValues[1] }.toMutableSet()
            repos[repoName] = RepoInfo(file, entityType, methods)
        }

        if (repos.isEmpty()) return files to changes

        // 2. Entity 필드 파싱
        data class EntityField(val name: String, val type: String)
        val entities = mutableMapOf<String, List<EntityField>>()

        for (file in files.filter { it.path.endsWith(".kt") }) {
            if (!file.content.contains("@Entity")) continue
            val classMatch = Regex("""(?:data\s+)?class\s+(\w+)\s*\(""").find(file.content) ?: continue
            val entityName = classMatch.groupValues[1]
            val fieldPattern = Regex("""(?:val|var)\s+(\w+)\s*:\s*(\w+)""")
            entities[entityName] = fieldPattern.findAll(file.content)
                .map { EntityField(it.groupValues[1], it.groupValues[2]) }.toList()
        }

        // 3. Service 파일에서 누락 Repository 메서드 탐지
        for (file in files.filter { it.path.endsWith(".kt") &&
            (it.path.lowercase().contains("service") || it.content.contains("@Service")) }) {

            val injectionPattern = Regex("""(?:val|var)\s+(\w+)\s*:\s*(\w+Repository)""")
            for (injection in injectionPattern.findAll(file.content)) {
                val varName = injection.groupValues[1]
                val repoType = injection.groupValues[2]
                val repoInfo = repos[repoType] ?: continue
                val entityFields = entities[repoInfo.entityType] ?: emptyList()

                val callPattern = Regex("""${Regex.escape(varName)}\.(\w+)\(""")
                for (call in callPattern.findAll(file.content)) {
                    val methodName = call.groupValues[1]
                    if (methodName in JPA_STANDARD_METHODS) continue
                    if (methodName in repoInfo.methods) continue

                    // findBy/findAllBy 메서드만 자동 추가
                    val fieldSuffix = when {
                        methodName.startsWith("findBy") -> methodName.removePrefix("findBy")
                        methodName.startsWith("findAllBy") -> methodName.removePrefix("findAllBy")
                        methodName.startsWith("deleteBy") -> methodName.removePrefix("deleteBy")
                        methodName.startsWith("countBy") -> methodName.removePrefix("countBy")
                        methodName.startsWith("existsBy") -> methodName.removePrefix("existsBy")
                        else -> continue
                    }

                    val paramName = fieldSuffix.replaceFirstChar { it.lowercase() }
                    val fieldType = entityFields.find { it.name == paramName }?.type
                        ?: if (paramName.endsWith("Id") || paramName == "id") "Long" else "String"

                    val returnType = when {
                        methodName.startsWith("findBy") || methodName.startsWith("findAllBy") -> "List<${repoInfo.entityType}>"
                        methodName.startsWith("deleteBy") -> "Unit"
                        methodName.startsWith("countBy") -> "Long"
                        methodName.startsWith("existsBy") -> "Boolean"
                        else -> "List<${repoInfo.entityType}>"
                    }

                    val methodDecl = "    fun $methodName($paramName: $fieldType): $returnType"
                    val repoContent = repoInfo.file.content
                    val closingBraceIdx = repoContent.lastIndexOf('}')
                    if (closingBraceIdx >= 0) {
                        val newContent = repoContent.substring(0, closingBraceIdx) +
                            "$methodDecl\n" + repoContent.substring(closingBraceIdx)
                        repoInfo.file = GeneratedFile(repoInfo.file.path, newContent)
                        repoInfo.methods.add(methodName)
                        changes.add(IntegrityChange(repoInfo.file.path, "MissingRepositoryMethod",
                            "Added missing method: $methodName($paramName: $fieldType): $returnType", true))
                    }
                }
            }
        }

        // 4. 업데이트된 파일 목록 구성
        val repoFileMap = repos.values.associate { it.file.path to it.file }
        val updatedFiles = files.map { repoFileMap[it.path] ?: it }
        return updatedFiles to changes
    }

    // ── Check 15: Kotlin 단일 인용부호(multi-char) → 이중 인용부호 수정 ──

    private fun checkKotlinSingleQuoteStrings(files: List<GeneratedFile>): Pair<List<GeneratedFile>, List<IntegrityChange>> {
        val changes = mutableListOf<IntegrityChange>()
        val singleQuotePattern = Regex("""'([^'\\]{2,})'""")

        val updatedFiles = files.map { file ->
            if (!file.path.endsWith(".kt")) return@map file
            if (!singleQuotePattern.containsMatchIn(file.content)) return@map file

            val newContent = singleQuotePattern.replace(file.content) { match ->
                "\"${match.groupValues[1]}\""
            }

            changes.add(IntegrityChange(file.path, "SingleQuoteFix",
                "Fixed multi-char single quotes → double quotes (Kotlin char literal constraint)", true))
            GeneratedFile(file.path, newContent)
        }
        return updatedFiles to changes
    }

    // ── Check 16: 깨진 @Value 어노테이션 복구 ──

    private fun checkBrokenValueAnnotations(files: List<GeneratedFile>): Pair<List<GeneratedFile>, List<IntegrityChange>> {
        val changes = mutableListOf<IntegrityChange>()
        val updatedFiles = files.map { file ->
            if (!file.path.endsWith(".kt")) return@map file

            var content = file.content
            var modified = false

            // Pattern 1: @Value("" 뒤에 줄바꿈 (closing paren 누락 — JSON 직렬화 시 ${} 값 유실)
            // @Value(""\n    private lateinit var secretKey: String
            //   → private val secretKey = "default-jwt-secret-key-32characters!"
            val brokenValueLateinitPattern = Regex(
                """@Value\(""\s*\n\s*(private\s+)?lateinit\s+var\s+(\w+)\s*:\s*String""")
            val brokenMatch = brokenValueLateinitPattern.find(content)
            if (brokenMatch != null) {
                content = content.replace(brokenMatch.value,
                    "private val ${brokenMatch.groupValues[2]} = \"default-jwt-secret-key-32characters!\"")
                modified = true
                changes.add(IntegrityChange(file.path, "BrokenValueAnnotation",
                    "Replaced broken @Value(\"\") lateinit → hardcoded default for ${brokenMatch.groupValues[2]}", true))
            }

            // Pattern 2: @Value("") with empty value on secret-like fields
            val emptyValuePattern = Regex(
                """@Value\(""\)\s*\n\s*(private\s+)?(?:lateinit\s+)?(?:val|var)\s+(\w*(?:[Ss]ecret|[Kk]ey)\w*)\s*:\s*String""")
            val emptyMatch = emptyValuePattern.find(content)
            if (emptyMatch != null && !modified) {
                content = content.replace(emptyMatch.value,
                    "private val ${emptyMatch.groupValues[2]} = \"default-jwt-secret-key-32characters!\"")
                modified = true
                changes.add(IntegrityChange(file.path, "BrokenValueAnnotation",
                    "Replaced empty @Value(\"\") → hardcoded default for ${emptyMatch.groupValues[2]}", true))
            }

            // Pattern 3: @Value("") on expiration-like fields → provide default value
            val emptyExpirationPattern = Regex(
                """@Value\(""\)\s*\n\s*(private\s+)?(?:val|var)\s+(\w*(?:[Ee]xpir|[Tt]imeout|[Tt]tl)\w*)\s*:\s*Long\s*=\s*0""")
            val expMatch = emptyExpirationPattern.find(content)
            if (expMatch != null) {
                content = content.replace(expMatch.value,
                    "private val ${expMatch.groupValues[2]}: Long = 86400000 // 24 hours")
                modified = true
                changes.add(IntegrityChange(file.path, "BrokenValueAnnotation",
                    "Replaced empty @Value(\"\") → 24h default for ${expMatch.groupValues[2]}", true))
            }

            if (modified) GeneratedFile(file.path, content) else file
        }
        return updatedFiles to changes
    }

    // ── Check 17: Repository ID 타입 ↔ Service 파라미터 타입 정합성 ──

    private fun checkRepoIdTypeAlignment(files: List<GeneratedFile>): Pair<List<GeneratedFile>, List<IntegrityChange>> {
        val changes = mutableListOf<IntegrityChange>()

        // 1. Repository → ID 타입 매핑: RepoClassName → IdType
        val repoIdTypes = mutableMapOf<String, String>()
        for (file in files.filter { it.path.endsWith(".kt") }) {
            val repoPattern = Regex("""interface\s+(\w+Repository)\s*:\s*JpaRepository<\w+,\s*(\w+\??)>""")
            val match = repoPattern.find(file.content) ?: continue
            repoIdTypes[match.groupValues[1]] = match.groupValues[2].removeSuffix("?")
        }

        if (repoIdTypes.isEmpty()) return files to changes

        // 2. Service 파일에서 Repository 주입 → ID 타입 불일치 수정
        val updatedFiles = files.map { file ->
            if (!file.path.endsWith(".kt")) return@map file
            if (!file.content.contains("@Service") && !file.path.lowercase().contains("service")) return@map file

            var content = file.content
            var modified = false

            // Repository 주입 탐지: val xxxRepository: XxxRepository
            val injectionPattern = Regex("""(?:val|var)\s+(\w+)\s*:\s*(\w+Repository)""")
            for (injection in injectionPattern.findAll(content)) {
                val varName = injection.groupValues[1]
                val repoType = injection.groupValues[2]
                val expectedIdType = repoIdTypes[repoType] ?: continue

                // Long이 기본값이므로, Long이 아닌 ID 타입일 때만 수정
                if (expectedIdType == "Long") continue

                // 메서드에서 id: Long 파라미터를 id: ExpectedType으로 변경
                // Pattern: fun methodName(id: Long, ...) where the method uses repository.xxxById(id)
                val methodPattern = Regex("""(fun\s+\w+\s*\([^)]*)\bid\s*:\s*Long\b([^)]*\))""")
                for (methodMatch in methodPattern.findAll(content)) {
                    val fullMethod = methodMatch.value
                    // 이 메서드가 해당 repository를 사용하는지 확인
                    val methodBodyStart = content.indexOf(fullMethod) + fullMethod.length
                    val methodBodyEnd = content.indexOf("\n    fun ", methodBodyStart).let {
                        if (it < 0) content.length else it
                    }
                    val methodBody = content.substring(methodBodyStart, methodBodyEnd)

                    if (methodBody.contains("$varName.") || methodBody.contains(varName)) {
                        val newMethod = fullMethod.replace("id: Long", "id: $expectedIdType")
                        content = content.replace(fullMethod, newMethod)
                        modified = true
                        changes.add(IntegrityChange(file.path, "RepoIdTypeAlignment",
                            "Changed id: Long → id: $expectedIdType (matching $repoType ID type)", true))
                    }
                }
            }

            if (modified) GeneratedFile(file.path, content) else file
        }

        return updatedFiles to changes
    }

    // ── Check 18: Kotlin secondary constructor → primary constructor 변환 ──

    private fun checkSecondaryToPrimaryConstructor(files: List<GeneratedFile>): Pair<List<GeneratedFile>, List<IntegrityChange>> {
        val changes = mutableListOf<IntegrityChange>()
        val updatedFiles = files.map { file ->
            if (!file.path.endsWith(".kt")) return@map file

            var content = file.content
            var modified = false

            // Pattern: class ClassName : SuperClass() {
            //     private val field: Type
            //     constructor(field: Type) { this.field = field }
            // }
            // → class ClassName(private val field: Type) : SuperClass() {
            val classWithSecondaryCtorPattern = Regex(
                """(class\s+(\w+))\s*:\s*(\w+)\(\)\s*\{\s*\n""" +
                """\s+private\s+(?:val|var)\s+(\w+)\s*:\s*(\w+)\s*\n""" +
                """\s*\n?\s+constructor\(\s*(\w+)\s*:\s*(\w+)\s*\)\s*\{""" +
                """\s*\n\s+this\.(\w+)\s*=\s*(\w+)\s*\n\s+\}""",
                RegexOption.MULTILINE
            )

            val ctorMatch = classWithSecondaryCtorPattern.find(content)
            if (ctorMatch != null) {
                val className = ctorMatch.groupValues[2]
                val superClass = ctorMatch.groupValues[3]
                val fieldName = ctorMatch.groupValues[4]
                val fieldType = ctorMatch.groupValues[5]

                // 전체 매칭된 부분을 primary constructor 패턴으로 교체
                val replacement = "class $className(private val $fieldName: $fieldType) : $superClass() {\n"
                content = content.replace(ctorMatch.value, replacement)
                modified = true
                changes.add(IntegrityChange(file.path, "SecondaryToPrimaryConstructor",
                    "Converted secondary constructor → primary constructor for $className", true))
            }

            if (modified) GeneratedFile(file.path, content) else file
        }
        return updatedFiles to changes
    }

    // ── Check 19: build.gradle.kts 의존성 표기 수정 (그룹 누락) ──

    private fun checkInvalidDependencyNotation(files: List<GeneratedFile>): Pair<List<GeneratedFile>, List<IntegrityChange>> {
        val changes = mutableListOf<IntegrityChange>()
        val gradleFile = files.find { it.path.endsWith("build.gradle.kts") } ?: return files to changes

        var content = gradleFile.content
        var modified = false

        // artifact-only 의존성 찾기: implementation("some-artifact") where no ':' in string
        val depPattern = Regex("""(implementation|runtimeOnly|testImplementation|api|compileOnly)\("([^":]+)"\)""")

        for (match in depPattern.findAll(content)) {
            val depType = match.groupValues[1]
            val artifact = match.groupValues[2]

            val group = findGroupForArtifact(artifact) ?: continue

            val oldDep = match.value
            val newDep = "$depType(\"$group:$artifact\")"
            content = content.replace(oldDep, newDep)
            modified = true
            changes.add(IntegrityChange(gradleFile.path, "InvalidDependencyNotation",
                "Fixed dependency notation: $artifact → $group:$artifact", true))
        }

        if (!modified) return files to changes

        val updatedFiles = files.map { if (it.path == gradleFile.path) GeneratedFile(it.path, content) else it }
        return updatedFiles to changes
    }

    private fun findGroupForArtifact(artifact: String): String? {
        // Exact match
        ARTIFACT_EXACT_GROUPS[artifact]?.let { return it }

        // Prefix match
        for ((prefix, group) in ARTIFACT_GROUP_PREFIXES) {
            if (artifact.startsWith(prefix)) return group
        }

        return null
    }

    // ── Check 20: @Id @GeneratedValue 필드의 잘못된 custom getter 제거 ──

    private fun checkMalformedIdGetter(files: List<GeneratedFile>): Pair<List<GeneratedFile>, List<IntegrityChange>> {
        val changes = mutableListOf<IntegrityChange>()
        val updatedFiles = files.map { file ->
            if (!file.path.endsWith(".kt")) return@map file
            if (!file.content.contains("@Entity")) return@map file
            if (!file.content.contains("@Id")) return@map file

            val lines = file.content.lines().toMutableList()
            val linesToRemove = mutableSetOf<Int>()

            for (i in lines.indices) {
                val trimmed = lines[i].trim()
                if (trimmed != "@Id") continue

                // @Id 이후 @GeneratedValue와 property 선언 찾기
                var hasGeneratedValue = false
                var propLineIdx = -1
                for (j in i + 1 until minOf(i + 5, lines.size)) {
                    val nextTrimmed = lines[j].trim()
                    if (nextTrimmed.startsWith("@GeneratedValue")) hasGeneratedValue = true
                    if (nextTrimmed.startsWith("var ") || nextTrimmed.startsWith("val ")) {
                        propLineIdx = j
                        break
                    }
                }
                if (propLineIdx < 0 || !hasGeneratedValue) continue

                // property 다음 줄이 get() = 이면 제거
                val nextIdx = propLineIdx + 1
                if (nextIdx < lines.size) {
                    val nextLine = lines[nextIdx].trim()
                    if (nextLine.startsWith("get() =") || nextLine.startsWith("get()=")) {
                        linesToRemove.add(nextIdx)
                        changes.add(IntegrityChange(file.path, "MalformedIdGetter",
                            "Removed invalid getter on @Id field: ${nextLine.take(60)}", true))
                    }
                }
            }

            if (linesToRemove.isEmpty()) return@map file

            val newLines = lines.filterIndexed { idx, _ -> idx !in linesToRemove }
            GeneratedFile(file.path, newLines.joinToString("\n"))
        }
        return updatedFiles to changes
    }

    // ── Check 12: 중복 클래스 선언 제거 ──

    private fun checkDuplicateClasses(files: List<GeneratedFile>): Pair<List<GeneratedFile>, List<IntegrityChange>> {
        val changes = mutableListOf<IntegrityChange>()

        // 각 .kt 파일에서 선언된 클래스/인터페이스 이름 추출
        data class ClassDecl(val name: String, val packageName: String, val file: GeneratedFile)

        val allDecls = mutableListOf<ClassDecl>()
        for (file in files) {
            if (!file.path.endsWith(".kt")) continue
            val pkg = file.content.lines()
                .firstOrNull { it.trimStart().startsWith("package ") }
                ?.trim()?.removePrefix("package ")?.trim() ?: ""
            val classPattern = Regex("""(?:class|interface|object|enum class)\s+(\w+)""")
            for (match in classPattern.findAll(file.content)) {
                allDecls.add(ClassDecl(match.groupValues[1], pkg, file))
            }
        }

        // 같은 이름의 클래스가 같은 패키지의 **다른 파일**에 있으면 중복
        // 같은 파일 내 여러 선언(e.g., data class + companion)은 제외
        val duplicateGroups = allDecls
            .groupBy { "${it.packageName}.${it.name}" }  // 패키지+이름으로 그룹
            .filter { (_, decls) ->
                val distinctPaths = decls.map { it.file.path }.toSet()
                distinctPaths.size > 1  // 다른 파일에서 선언된 경우만
            }

        log.info("[INTEGRITY] DuplicateClassRemoval scan: {} total declarations, {} duplicate groups",
            allDecls.size, duplicateGroups.size)
        for ((key, decls) in duplicateGroups) {
            log.info("[INTEGRITY] DuplicateClassRemoval found: {} in {} files: {}",
                key, decls.size, decls.map { it.file.path })
        }

        if (duplicateGroups.isEmpty()) return files to changes

        val pathsToRemove = mutableSetOf<String>()
        for ((qualifiedName, decls) in duplicateGroups) {
            val className = qualifiedName.substringAfterLast(".")
            // 올바른 패키지 경로에 있는 파일 우선 (path에 패키지 구조가 반영된 것)
            // 같은 파일의 여러 선언은 1개로 통합
            val distinctFileDecls = decls.distinctBy { it.file.path }
            val preferred = distinctFileDecls.maxByOrNull { decl ->
                val expectedPath = decl.packageName.replace(".", "/")
                if (decl.file.path.contains(expectedPath)) 100 + decl.file.content.length
                else decl.file.content.length
            }
            for (decl in distinctFileDecls) {
                if (decl.file.path != preferred?.file?.path) {
                    pathsToRemove.add(decl.file.path)
                    changes.add(IntegrityChange(decl.file.path, "DuplicateClassRemoval",
                        "Removed duplicate declaration of '$className' (kept ${preferred?.file?.path})", true))
                }
            }
        }

        if (pathsToRemove.isEmpty()) return files to changes

        val updatedFiles = files.filter { it.path !in pathsToRemove }
        return updatedFiles to changes
    }
}
