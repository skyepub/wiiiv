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
        "SecurityFilterChain" to "org.springframework.security.web.SecurityFilterChain",
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

            // Check 30: 패키지-디렉토리 불일치 수정 (LLM이 src/main/kotlin/entity/ 생성했지만 package가 com.foo.entity인 경우)
            current = runCheck("PackagePathAlignment", current, changes) { checkPackagePathAlignment(it) }

            // Check 12: 중복 클래스 선언 제거 (멀티턴 생성 시 다른 경로에 같은 클래스 중복)
            current = runCheck("DuplicateClassRemoval", current, changes) { checkDuplicateClasses(it) }

            // Check 1: 의존성 완전성
            current = runCheck("DependencyCompleteness", current, changes) { checkDependencyCompleteness(it) }

            // Check 19: build.gradle.kts 의존성 표기 수정 (그룹 누락 시 자동 추가)
            current = runCheck("InvalidDependencyNotation", current, changes) { checkInvalidDependencyNotation(it) }

            // Check 32: build.gradle.kts 중복 의존성 제거 + 버전 없는 의존성 보정
            current = runCheck("DuplicateGradleDependency", current, changes) { checkDuplicateGradleDependencies(it) }

            // Check 13: @Entity data class val → var 변환 (JPA requires mutable properties)
            current = runCheck("EntityValToVar", current, changes) { checkEntityValToVar(it) }

            // Check 22: @Entity에 @Id 필드 누락 시 자동 추가
            current = runCheck("EntityMissingId", current, changes) { checkEntityMissingId(it) }

            // Check 25: @Entity @Id가 body에 있으면 constructor로 이동 (copy() 호환)
            current = runCheck("EntityIdBodyToConstructor", current, changes) { checkEntityIdBodyToConstructor(it) }

            // Check 27: Entity 깨진 computed getter 수정 (@Column get() = expr → var field: Type)
            current = runCheck("BrokenComputedGetter", current, changes) { checkBrokenComputedGetter(it) }

            // Check 20: @Id @GeneratedValue 필드의 잘못된 custom getter 제거
            current = runCheck("MalformedIdGetter", current, changes) { checkMalformedIdGetter(it) }

            // Check 36: LLM이 남긴 잔여 백슬래시 제거 (\@ → @, )\n에서의 \+공백 등)
            current = runCheck("StrayBackslash", current, changes) { checkStrayBackslash(it) }

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

            // Check 33: @Entity에 대응하는 Repository가 없으면 자동 생성
            current = runCheck("MissingEntityRepository", current, changes) { checkMissingEntityRepository(it) }

            // Check 4: 크로스 레퍼런스 무결성
            current = runCheck("CrossReference", current, changes) { checkCrossReference(it) }

            // DuplicateClassRemoval 2차 — CrossReference 스텁 + 멀티턴 중복 제거
            current = runCheck("DuplicateClassRemoval2", current, changes) { checkDuplicateClasses(it) }

            // Check 26: Controller/Service에서 선언 없이 사용하는 Repository 필드 자동 주입
            current = runCheck("ControllerMissingInjection", current, changes) { checkControllerMissingInjection(it) }

            // Check 14: Service/Controller가 호출하는데 Repository에 선언 안 된 메서드 자동 추가
            current = runCheck("MissingRepositoryMethod", current, changes) { checkMissingRepositoryMethods(it) }

            // Check 34: @ManyToOne 역방향 @OneToMany 컬렉션이 참조되면 Entity에 자동 추가
            current = runCheck("MissingOneToMany", current, changes) { checkMissingOneToMany(it) }

            // Check 31: 프로젝트 내부 클래스 import 자동 추가 (다른 파일에서 선언된 클래스 참조 시)
            current = runCheck("ProjectInternalImport", current, changes) { checkProjectInternalImports(it) }

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

            // Check 28: JwtAuthFilter 빈 메서드 → JwtProvider 위임
            current = runCheck("JwtAuthFilterDelegation", current, changes) { checkJwtAuthFilterDelegation(it) }

            // Check 17: Repository ID 타입 ↔ Service 파라미터 타입 정합성
            current = runCheck("RepoIdTypeAlignment", current, changes) { checkRepoIdTypeAlignment(it) }

            // Check 23: Controller↔Service ID 타입 정합성 + Service .copy() @Id 필드명 정합성
            current = runCheck("ControllerServiceIdAlignment", current, changes) { checkControllerServiceIdAlignment(it) }

            // Check 18: Kotlin secondary constructor → primary constructor 변환
            current = runCheck("SecondaryToPrimaryConstructor", current, changes) { checkSecondaryToPrimaryConstructor(it) }

            // Check 24: 테스트 파일 무결성 (의존성 추가 + 깨진 테스트 제거)
            current = runCheck("TestFileIntegrity", current, changes) { checkTestFileIntegrity(it) }

            // Check 29: 누락된 .toDto() 확장 함수 생성
            current = runCheck("MissingDtoExtension", current, changes) { checkMissingDtoExtension(it) }

            // Check 5: data.sql ↔ Entity 정합성 (경고만)
            runWarnCheck("DataSqlEntityAlignment", current, warnings) { checkDataSqlEntityAlignment(it) }

            // Check 35: 빈 함수 body에 TODO() 삽입 (컴파일 에러 방지)
            current = runCheck("EmptyFunctionBody", current, changes) { checkEmptyFunctionBody(it) }

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

            val stubContent = generateStub(packageName, className, files)
            newFiles.add(GeneratedFile(filePath, stubContent))
            changes.add(IntegrityChange(filePath, "CrossReference", "Generated stub for missing class: $className", true))
        }

        return (files + newFiles) to changes
    }

    private fun generateStub(packageName: String, className: String, files: List<GeneratedFile> = emptyList()): String {
        val sb = StringBuilder()
        sb.appendLine("package $packageName")
        sb.appendLine()

        when {
            className.endsWith("Repository") -> {
                // Entity 타입 추론: FooRepository → Foo
                val inferredEntity = className.removeSuffix("Repository")
                val entityFile = files.find { f ->
                    f.path.endsWith(".kt") && f.content.contains("@Entity") &&
                        Regex("""(?:data\s+)?class\s+$inferredEntity\b""").containsMatchIn(f.content)
                }
                val entityType = if (entityFile != null) inferredEntity else "Any"
                val entityPkg = if (entityFile != null) {
                    entityFile.content.lines().firstOrNull { it.trimStart().startsWith("package ") }
                        ?.trim()?.removePrefix("package ")?.trim()
                } else null

                sb.appendLine("import org.springframework.data.jpa.repository.JpaRepository")
                sb.appendLine("import org.springframework.stereotype.Repository")
                if (entityPkg != null) sb.appendLine("import $entityPkg.$entityType")
                sb.appendLine()
                sb.appendLine("@Repository")
                sb.appendLine("interface $className : JpaRepository<$entityType, Long>")
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

    // ── Check 29: 누락된 .toDto() 확장 함수 생성 ──
    //    Service에서 entity.toDto()를 호출하지만 확장 함수가 없을 때 자동 생성

    private fun checkMissingDtoExtension(files: List<GeneratedFile>): Pair<List<GeneratedFile>, List<IntegrityChange>> {
        val changes = mutableListOf<IntegrityChange>()

        // 1. Entity 클래스와 필드 수집
        data class EntityInfo(val name: String, val pkg: String, val fields: List<Pair<String, String>>, val file: GeneratedFile)
        val entities = mutableMapOf<String, EntityInfo>()
        for (file in files.filter { it.path.endsWith(".kt") && it.content.contains("@Entity") }) {
            val classMatch = Regex("""(?:data\s+)?class\s+(\w+)\s*\(""").find(file.content) ?: continue
            val entityName = classMatch.groupValues[1]
            val pkg = file.content.lines().firstOrNull { it.trimStart().startsWith("package ") }
                ?.trim()?.removePrefix("package ")?.trim() ?: continue
            val fields = Regex("""(?:var|val)\s+(\w+)\s*:\s*([^\s,=]+)""")
                .findAll(file.content).map { it.groupValues[1] to it.groupValues[2] }.toList()
            entities[entityName] = EntityInfo(entityName, pkg, fields, file)
        }

        // 2. DTO 클래스와 필드 수집
        data class DtoInfo(val name: String, val pkg: String, val fields: List<Pair<String, String>>)
        val dtos = mutableMapOf<String, DtoInfo>()
        for (file in files.filter { it.path.endsWith(".kt") &&
            (it.path.lowercase().contains("dto") || it.content.contains("Dto(")) }) {
            val classMatch = Regex("""data\s+class\s+(\w+Dto)\s*\(""").find(file.content) ?: continue
            val dtoName = classMatch.groupValues[1]
            val pkg = file.content.lines().firstOrNull { it.trimStart().startsWith("package ") }
                ?.trim()?.removePrefix("package ")?.trim() ?: continue
            val fields = Regex("""(?:var|val)\s+(\w+)\s*:\s*([^\s,=)]+)""")
                .findAll(file.content).map { it.groupValues[1] to it.groupValues[2] }.toList()
            dtos[dtoName] = DtoInfo(dtoName, pkg, fields)
        }

        // 3. .toDto() 호출이 있지만 확장 함수가 없는 경우 탐지
        val toDtoPattern = Regex("""\.toDto\(\)""")
        val existingExtensions = files.any { it.content.contains("fun ") && it.content.contains(".toDto()") &&
            it.content.contains("=") }

        if (existingExtensions) return files to changes  // 이미 확장 함수가 있으면 스킵

        val neededExtensions = mutableSetOf<String>() // entity names
        for (file in files.filter { it.path.endsWith(".kt") }) {
            if (!toDtoPattern.containsMatchIn(file.content)) continue
            // entity.toDto() 패턴에서 entity 타입 추론
            for (entityName in entities.keys) {
                val dtoName = "${entityName}Dto"
                if (dtoName in dtos && file.content.contains(dtoName)) {
                    neededExtensions.add(entityName)
                }
            }
        }

        if (neededExtensions.isEmpty()) return files to changes

        // 4. 확장 함수 파일 생성
        val newFiles = mutableListOf<GeneratedFile>()
        for (entityName in neededExtensions) {
            val entity = entities[entityName] ?: continue
            val dtoName = "${entityName}Dto"
            val dto = dtos[dtoName] ?: continue

            // DTO 필드와 Entity 필드의 교집합으로 매핑 생성
            val mappings = dto.fields.mapNotNull { (dtoField, _) ->
                val entityField = entity.fields.find { it.first == dtoField }
                if (entityField != null) "    $dtoField = this.$dtoField" else null
            }

            if (mappings.isEmpty()) continue

            val extensionContent = buildString {
                appendLine("package ${entity.pkg}")
                appendLine()
                appendLine("import ${dto.pkg}.$dtoName")
                appendLine()
                appendLine("fun $entityName.toDto() = $dtoName(")
                appendLine(mappings.joinToString(",\n"))
                appendLine(")")
            }

            val baseSrcDir = entity.file.path.substringBefore("src/main/kotlin")
            val pkgPath = entity.pkg.replace(".", "/")
            val filePath = "${baseSrcDir}src/main/kotlin/$pkgPath/${entityName}Extensions.kt"

            newFiles.add(GeneratedFile(filePath, extensionContent))
            changes.add(IntegrityChange(filePath, "MissingDtoExtension",
                "Generated toDto() extension for $entityName → $dtoName", true))
        }

        return (files + newFiles) to changes
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

    // ── Check 35: 빈 함수 body에 TODO() 삽입 ──

    private fun checkEmptyFunctionBody(files: List<GeneratedFile>): Pair<List<GeneratedFile>, List<IntegrityChange>> {
        val changes = mutableListOf<IntegrityChange>()
        val updatedFiles = files.map { file ->
            if (!file.path.endsWith(".kt") || !file.path.contains("src/main/")) return@map file

            var content = file.content
            var modified = false

            // 반환 타입이 있는 함수에서 body가 비어있거나 주석만 있으면 TODO() 추가
            val funcPattern = Regex("""fun\s+(\w+)\s*\([^)]*\)\s*:\s*\S+[^{]*\{""")
            for (match in funcPattern.findAll(content)) {
                val funcName = match.groupValues[1]
                val braceStart = match.range.last
                val afterBrace = content.substring(braceStart + 1)
                val closingIdx = findMatchingBrace(afterBrace)
                if (closingIdx < 0) continue

                val body = afterBrace.substring(0, closingIdx).trim()
                val bodyWithoutComments = body.lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("//") }
                    .joinToString("")

                if (bodyWithoutComments.isEmpty()) {
                    // 빈 body → TODO() 삽입
                    val insertPoint = braceStart + 1
                    val indent = "        "
                    val todoLine = "\n${indent}TODO(\"Not yet implemented\")\n    "
                    content = content.substring(0, insertPoint) + todoLine + content.substring(insertPoint + closingIdx)
                    modified = true
                    changes.add(IntegrityChange(file.path, "EmptyFunctionBody",
                        "Added TODO() to empty function '$funcName'", true))
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

    // ── Check 28: JwtAuthFilter 빈 메서드 → JwtProvider 위임 ──

    private fun checkJwtAuthFilterDelegation(files: List<GeneratedFile>): Pair<List<GeneratedFile>, List<IntegrityChange>> {
        val changes = mutableListOf<IntegrityChange>()

        val jwtAuthFilter = files.find {
            it.path.endsWith("JwtAuthFilter.kt") && it.content.contains("OncePerRequestFilter")
        } ?: return files to changes

        val jwtProvider = files.find {
            it.path.endsWith("JwtProvider.kt") && it.content.contains("class JwtProvider")
        } ?: return files to changes

        // JwtAuthFilter에 빈 메서드가 있는지 확인
        val emptyMethodPattern = Regex("""private\s+fun\s+(validateToken|getUsernameFromToken)\([^)]*\)\s*:\s*\w+\s*\{\s*(?://[^\n]*)?\s*\}""")
        if (!emptyMethodPattern.containsMatchIn(jwtAuthFilter.content)) return files to changes

        var content = jwtAuthFilter.content

        // JwtProvider를 constructor injection으로 추가 (없으면)
        if (!content.contains("jwtProvider") && !content.contains("JwtProvider")) {
            // 현재: class JwtAuthFilter : OncePerRequestFilter()
            // 변경: class JwtAuthFilter(private val jwtProvider: JwtProvider) : OncePerRequestFilter()
            content = content.replace(
                Regex("""class\s+JwtAuthFilter\s*(?:\(\s*\))?\s*:\s*OncePerRequestFilter\(\)"""),
                "class JwtAuthFilter(private val jwtProvider: JwtProvider) : OncePerRequestFilter()"
            )

            // JwtProvider import 추가
            val jwtProviderPkg = jwtProvider.content.lines()
                .firstOrNull { it.trimStart().startsWith("package ") }
                ?.trim()?.removePrefix("package ")?.trim()
            if (jwtProviderPkg != null) {
                val importLine = "import $jwtProviderPkg.JwtProvider"
                if (!content.contains(importLine)) {
                    val lastImport = content.lastIndexOf("import ")
                    val insertIdx = content.indexOf('\n', lastImport) + 1
                    if (insertIdx > 0) {
                        content = content.substring(0, insertIdx) + "$importLine\n" + content.substring(insertIdx)
                    }
                }
            }
        }

        // 빈 validateToken → JwtProvider 위임
        content = content.replace(
            Regex("""private\s+fun\s+validateToken\((\w+)\s*:\s*String\)\s*:\s*Boolean\s*\{\s*(?://[^\n]*)?\s*\}"""),
            "private fun validateToken(\$1: String): Boolean {\n        return try { jwtProvider.validateToken(\$1) } catch (e: Exception) { false }\n    }"
        )

        // 빈 getUsernameFromToken → JwtProvider 위임
        content = content.replace(
            Regex("""private\s+fun\s+getUsernameFromToken\((\w+)\s*:\s*String\)\s*:\s*String\s*\{\s*(?://[^\n]*)?\s*\}"""),
            "private fun getUsernameFromToken(\$1: String): String {\n        return jwtProvider.getUsernameFromToken(\$1)\n    }"
        )

        if (content == jwtAuthFilter.content) return files to changes

        val updatedFiles = files.map {
            if (it.path == jwtAuthFilter.path) GeneratedFile(it.path, content) else it
        }
        changes.add(IntegrityChange(jwtAuthFilter.path, "JwtAuthFilterDelegation",
            "Delegated empty methods to JwtProvider", true))

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

    // ── Check 25: @Entity data class의 @Id가 body에 있으면 constructor로 이동 ──
    //    body에 있으면 copy()로 접근 불가 → .copy(id = id) 실패

    private fun checkEntityIdBodyToConstructor(files: List<GeneratedFile>): Pair<List<GeneratedFile>, List<IntegrityChange>> {
        val changes = mutableListOf<IntegrityChange>()
        val updatedFiles = files.map { file ->
            if (!file.path.endsWith(".kt")) return@map file
            if (!file.content.contains("@Entity")) return@map file
            if (!file.content.contains("@Id")) return@map file

            // data class인지 확인
            val dataClassMatch = Regex("""data\s+class\s+(\w+)\s*\(""").find(file.content) ?: return@map file

            // @Id가 constructor 안에 있는지 확인 (constructor 범위 = 첫 '(' ~ 짝 ')')
            val constructorStart = dataClassMatch.range.last // '(' 위치
            var depth = 1
            var constructorEnd = constructorStart + 1
            val content = file.content
            while (constructorEnd < content.length && depth > 0) {
                when (content[constructorEnd]) {
                    '(' -> depth++
                    ')' -> depth--
                }
                if (depth > 0) constructorEnd++
            }

            val constructorBody = content.substring(constructorStart, constructorEnd + 1)
            if (constructorBody.contains("@Id")) return@map file  // 이미 constructor에 있음

            // body에서 @Id 필드 찾기
            val bodyStart = content.indexOf('{', constructorEnd)
            if (bodyStart < 0) return@map file
            val bodyContent = content.substring(bodyStart)

            // @Id ... var fieldName: Type = default 패턴 찾기
            val idFieldPattern = Regex("""(?s)(@Id\s+(?:@\w+(?:\([^)]*\))?[ \t]*\n?\s*)*)(var|val)\s+(\w+)\s*:\s*(\w+\??)\s*(?:=\s*([^\n,}]+))?""")
            val idMatch = idFieldPattern.find(bodyContent) ?: return@map file

            val fullMatch = idMatch.value
            val annotations = idMatch.groupValues[1].trim()
            val varType = idMatch.groupValues[2]
            val fieldName = idMatch.groupValues[3]
            val fieldType = idMatch.groupValues[4]
            val defaultValue = idMatch.groupValues[5].trim()

            // body에서 @Id 필드 라인 제거
            var newContent = content.substring(0, bodyStart + bodyContent.indexOf(fullMatch)) +
                content.substring(bodyStart + bodyContent.indexOf(fullMatch) + fullMatch.length)
            // 빈 줄 정리
            newContent = newContent.replace(Regex("""\{\s*\n\s*\n"""), "{\n")

            // body가 비어있으면 제거
            val emptyBodyPattern = Regex("""\)\s*\{\s*\}""")
            if (emptyBodyPattern.containsMatchIn(newContent)) {
                newContent = emptyBodyPattern.replace(newContent, ")")
            }

            // constructor에 @Id 필드를 첫 번째 파라미터로 삽입
            val insertPos = constructorStart + 1
            val indent = "    "
            val defaultStr = if (defaultValue.isNotEmpty()) " = $defaultValue" else ""
            val idParam = "\n${indent}$annotations $varType $fieldName: $fieldType$defaultStr,\n${indent}"

            newContent = newContent.substring(0, insertPos) + idParam + newContent.substring(insertPos).trimStart()

            changes.add(IntegrityChange(file.path, "EntityIdBodyToConstructor",
                "Moved @Id field '$fieldName' from body to constructor (copy() compatibility)", true))
            GeneratedFile(file.path, newContent)
        }
        return updatedFiles to changes
    }

    // ── Check 27: Entity 깨진 computed getter 수정 ──
    //    패턴: @Column(name = "foo") get() = expr → var foo: InferredType = defaultValue

    private fun checkBrokenComputedGetter(files: List<GeneratedFile>): Pair<List<GeneratedFile>, List<IntegrityChange>> {
        val changes = mutableListOf<IntegrityChange>()
        val updatedFiles = files.map { file ->
            if (!file.path.endsWith(".kt")) return@map file
            if (!file.content.contains("@Entity")) return@map file

            // 패턴: @Column(name = "fieldName")\n    get() = expr 또는 한 줄로
            val brokenGetterPattern = Regex(
                """@Column\s*\(\s*name\s*=\s*"(\w+)"\s*\)\s*\n?\s*get\(\)\s*=\s*([^\n]+)""")
            if (!brokenGetterPattern.containsMatchIn(file.content)) return@map file

            var content = file.content
            brokenGetterPattern.findAll(file.content).forEach { match ->
                val fieldName = match.groupValues[1]
                // camelCase 변환: subtotal → subtotal, total_cost → totalCost
                val camelName = fieldName.replace(Regex("_([a-z])")) { it.groupValues[1].uppercase() }

                // 타입 추론: BigDecimal이면 BigDecimal, 숫자면 기본 타입
                val expr = match.groupValues[2].trim()
                val inferredType = when {
                    expr.contains("BigDecimal") || expr.contains("multiply") -> "BigDecimal"
                    expr.contains("toDouble") -> "Double"
                    expr.contains("toInt") || expr.contains("size") -> "Int"
                    else -> "BigDecimal"
                }
                val defaultVal = when (inferredType) {
                    "BigDecimal" -> "BigDecimal.ZERO"
                    "Double" -> "0.0"
                    "Int" -> "0"
                    else -> "BigDecimal.ZERO"
                }

                // 깨진 패턴을 정상 var 선언으로 교체
                content = content.replace(match.value, "var $camelName: $inferredType = $defaultVal")

                changes.add(IntegrityChange(file.path, "BrokenComputedGetter",
                    "Fixed broken computed getter → var $camelName: $inferredType = $defaultVal", true))
            }

            GeneratedFile(file.path, content)
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

    // ── Check 26: Controller에서 선언 없이 사용하는 필드(Repository 등) 자동 주입 ──

    private fun checkControllerMissingInjection(files: List<GeneratedFile>): Pair<List<GeneratedFile>, List<IntegrityChange>> {
        val changes = mutableListOf<IntegrityChange>()

        val updatedFiles = files.map { file ->
            if (!file.path.endsWith(".kt")) return@map file
            if (!file.content.contains("@RestController") && !file.content.contains("@Controller") && !file.content.contains("@Service")) return@map file

            // 현재 constructor에 선언된 필드 수집
            val classPattern = Regex("""class\s+\w+\s*\(([^)]*)\)""", RegexOption.DOT_MATCHES_ALL)
            val classMatch = classPattern.find(file.content) ?: return@map file
            val constructorParams = classMatch.groupValues[1]
            val declaredFields = Regex("""(?:val|var)\s+(\w+)\s*:\s*(\w+)""")
                .findAll(constructorParams).map { it.groupValues[1] }.toSet()

            // 코드에서 사용된 xxxRepository.method() 패턴 수집
            val usedRepos = Regex("""(\w+Repository)\.(\w+)\(""")
                .findAll(file.content)
                .map { it.groupValues[1].replaceFirstChar { c -> c.lowercase() } }
                .toSet()

            // 선언되지 않은 Repository 필드 찾기
            val missingFields = usedRepos.filter { it !in declaredFields }
            if (missingFields.isEmpty()) return@map file

            var content = file.content
            // constructor에 필드 추가
            val insertPoint = classMatch.range.first + classMatch.value.indexOf(')')
            val additions = missingFields.joinToString("") { fieldName ->
                val typeName = fieldName.replaceFirstChar { it.uppercase() }
                ", private val $fieldName: $typeName"
            }

            content = content.substring(0, insertPoint) + additions + content.substring(insertPoint)

            // import 추가 (Repository 패키지 추론)
            val packageRoot = file.content.lines()
                .firstOrNull { it.trimStart().startsWith("package ") }
                ?.trim()?.removePrefix("package ")?.trim()
                ?.split(".")?.take(3)?.joinToString(".") ?: ""

            for (fieldName in missingFields) {
                val typeName = fieldName.replaceFirstChar { it.uppercase() }
                // 이미 import 되어 있으면 스킵
                if (content.contains("import") && content.contains(typeName)) continue
                val repoFile = files.find { f ->
                    f.path.endsWith("$typeName.kt") && f.content.contains("interface $typeName")
                }
                val repoPkg = repoFile?.content?.lines()
                    ?.firstOrNull { it.trimStart().startsWith("package ") }
                    ?.trim()?.removePrefix("package ")?.trim()
                if (repoPkg != null) {
                    val importLine = "import $repoPkg.$typeName"
                    if (!content.contains(importLine)) {
                        val lastImport = content.lastIndexOf("import ")
                        val insertIdx = content.indexOf('\n', lastImport) + 1
                        if (insertIdx > 0) {
                            content = content.substring(0, insertIdx) + "$importLine\n" + content.substring(insertIdx)
                        }
                    }
                }
            }

            changes.add(IntegrityChange(file.path, "ControllerMissingInjection",
                "Injected missing fields: ${missingFields.joinToString(", ")}", true))
            GeneratedFile(file.path, content)
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

            // 소문자 hallucination 패턴 수정: customizer.withDefaults() → Customizer.withDefaults()
            if (content.contains("customizer.withDefaults()") || content.contains("customizer .withDefaults()")) {
                content = content.replace("customizer.withDefaults()", "Customizer.withDefaults()")
                content = content.replace("customizer .withDefaults()", "Customizer.withDefaults()")
                modified = true
                changes.add(IntegrityChange(file.path, "HallucinatedClassFix",
                    "Fixed lowercase hallucination: customizer → Customizer", true))
            }

            // Java Map.of() / List.of() / Set.of() → Kotlin mapOf() / listOf() / setOf()
            val javaCollectionOfs = mapOf(
                "Map.of(" to "mapOf(",
                "List.of(" to "listOf(",
                "Set.of(" to "setOf(",
                "Map.entry(" to "mapOf(",  // rare but possible
            )
            for ((javaApi, kotlinApi) in javaCollectionOfs) {
                if (content.contains(javaApi)) {
                    content = content.replace(javaApi, kotlinApi)
                    modified = true
                    changes.add(IntegrityChange(file.path, "HallucinatedClassFix",
                        "Fixed Java API in Kotlin: $javaApi → $kotlinApi", true))
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

        // JPA 파생 쿼리 조건 접미사 (파라미터 불필요)
        val noParamSuffixes = listOf("True", "False", "IsNull", "IsNotNull", "NotNull")
        // JPA 파생 쿼리 조건 접미사 (파라미터 타입 변환)
        val stringCondSuffixes = listOf("Containing", "StartingWith", "EndingWith", "Like", "NotLike",
            "IgnoreCase", "ContainingIgnoreCase")

        // 3. Service + Controller 파일에서 누락 Repository 메서드 탐지
        for (file in files.filter { it.path.endsWith(".kt") &&
            (it.path.lowercase().contains("service") || it.content.contains("@Service") ||
             it.path.lowercase().contains("controller") || it.content.contains("@RestController") ||
             it.content.contains("@Controller")) }) {

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

                    // JPA 파생 쿼리 컨벤션 처리
                    val isNoParam = noParamSuffixes.any { fieldSuffix.endsWith(it) }
                    val stringCond = stringCondSuffixes.firstOrNull { fieldSuffix.endsWith(it) }

                    val cleanSuffix = when {
                        isNoParam -> {
                            val suffix = noParamSuffixes.first { fieldSuffix.endsWith(it) }
                            fieldSuffix.removeSuffix(suffix)
                        }
                        stringCond != null -> fieldSuffix.removeSuffix(stringCond)
                        else -> fieldSuffix
                    }

                    // 언더스코어 탐색 패턴 처리 (findBySupplierProducts_SkymallProductId)
                    val hasNavigation = cleanSuffix.contains("_")

                    val paramName = if (hasNavigation) {
                        cleanSuffix.substringAfterLast("_").replaceFirstChar { it.lowercase() }
                    } else {
                        cleanSuffix.replaceFirstChar { it.lowercase() }
                    }

                    val fieldType = when {
                        isNoParam -> null // 파라미터 없음
                        stringCond != null -> "String"
                        hasNavigation -> if (paramName.endsWith("Id") || paramName == "id") "Long" else "String"
                        else -> entityFields.find { it.name == paramName }?.type
                            ?: if (paramName.endsWith("Id") || paramName == "id") "Long" else "String"
                    }

                    val returnType = when {
                        methodName.startsWith("findBy") || methodName.startsWith("findAllBy") -> "List<${repoInfo.entityType}>"
                        methodName.startsWith("deleteBy") -> "Unit"
                        methodName.startsWith("countBy") -> "Long"
                        methodName.startsWith("existsBy") -> "Boolean"
                        else -> "List<${repoInfo.entityType}>"
                    }

                    val methodDecl = if (fieldType != null) {
                        "    fun $methodName($paramName: $fieldType): $returnType"
                    } else {
                        "    fun $methodName(): $returnType"
                    }
                    val repoContent = repoInfo.file.content
                    // Repository가 { } 없이 한 줄이면 { } 블록 추가
                    val hasBody = repoContent.contains('{')
                    if (!hasBody) {
                        // "interface FooRepository : JpaRepository<...>" → "interface FooRepository : JpaRepository<...> {\n  method\n}"
                        val interfaceLine = Regex("""(interface\s+\w+\s*:\s*JpaRepository<[^>]+>)\s*$""", RegexOption.MULTILINE)
                        val ifMatch = interfaceLine.find(repoContent)
                        if (ifMatch != null) {
                            val newContent = repoContent.substring(0, ifMatch.range.last + 1) +
                                " {\n$methodDecl\n}"
                            repoInfo.file = GeneratedFile(repoInfo.file.path, newContent)
                            repoInfo.methods.add(methodName)
                            val paramDesc = if (fieldType != null) "$paramName: $fieldType" else ""
                            changes.add(IntegrityChange(repoInfo.file.path, "MissingRepositoryMethod",
                                "Added missing method: $methodName($paramDesc): $returnType", true))
                        }
                    } else {
                        val closingBraceIdx = repoContent.lastIndexOf('}')
                        if (closingBraceIdx >= 0) {
                            val newContent = repoContent.substring(0, closingBraceIdx) +
                                "$methodDecl\n" + repoContent.substring(closingBraceIdx)
                            repoInfo.file = GeneratedFile(repoInfo.file.path, newContent)
                            repoInfo.methods.add(methodName)
                            val paramDesc = if (fieldType != null) "$paramName: $fieldType" else ""
                            changes.add(IntegrityChange(repoInfo.file.path, "MissingRepositoryMethod",
                                "Added missing method: $methodName($paramDesc): $returnType", true))
                        }
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

    // ── Check 33: @Entity에 대응하는 Repository가 없으면 자동 생성 ──

    private fun checkMissingEntityRepository(files: List<GeneratedFile>): Pair<List<GeneratedFile>, List<IntegrityChange>> {
        val changes = mutableListOf<IntegrityChange>()
        val newFiles = mutableListOf<GeneratedFile>()

        // 1. @Entity 클래스 수집: className → (packageName, filePath)
        val entities = mutableMapOf<String, Pair<String, String>>()
        for (file in files) {
            if (!file.path.endsWith(".kt")) continue
            if (!file.content.contains("@Entity")) continue
            val pkgMatch = Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE).find(file.content) ?: continue
            val pkg = pkgMatch.groupValues[1]
            val classMatch = Regex("""(?:data\s+)?class\s+(\w+)""").find(file.content) ?: continue
            val className = classMatch.groupValues[1]
            entities[className] = pkg to file.path
        }

        // 2. 기존 Repository 수집
        val existingRepos = mutableSetOf<String>()
        for (file in files) {
            if (!file.path.endsWith("Repository.kt")) continue
            val classMatch = Regex("""interface\s+(\w+Repository)""").find(file.content) ?: continue
            existingRepos.add(classMatch.groupValues[1])
        }

        // 3. @Entity에 대응하는 Repository가 없으면 생성
        for ((entityName, pkgAndPath) in entities) {
            val repoName = "${entityName}Repository"
            if (existingRepos.contains(repoName)) continue

            val (entityPkg, entityPath) = pkgAndPath
            // repository 패키지 추론: model/entity → repository
            val repoPkg = entityPkg
                .replace(".model", ".repository")
                .replace(".entity", ".repository")
            val repoPath = entityPath
                .replace("/model/", "/repository/")
                .replace("/entity/", "/repository/")
                .replace("$entityName.kt", "$repoName.kt")

            val repoContent = buildString {
                appendLine("package $repoPkg")
                appendLine()
                appendLine("import $entityPkg.$entityName")
                appendLine("import org.springframework.data.jpa.repository.JpaRepository")
                appendLine()
                appendLine("interface $repoName : JpaRepository<$entityName, Long>")
            }

            newFiles.add(GeneratedFile(repoPath, repoContent))
            changes.add(IntegrityChange(repoPath, "MissingEntityRepository",
                "Auto-generated repository for @Entity $entityName", true))
        }

        if (newFiles.isEmpty()) return files to changes
        return (files + newFiles) to changes
    }

    // ── Check 34: @ManyToOne 역방향 @OneToMany 컬렉션이 참조되면 Entity에 자동 추가 ──

    private fun checkMissingOneToMany(files: List<GeneratedFile>): Pair<List<GeneratedFile>, List<IntegrityChange>> {
        val changes = mutableListOf<IntegrityChange>()

        // 1. @ManyToOne 관계 수집: (childEntity, parentEntity, fieldName)
        data class ManyToOneRel(val childClass: String, val parentClass: String, val fieldName: String, val childPkg: String)
        val relations = mutableListOf<ManyToOneRel>()
        val entityClassNames = mutableMapOf<String, String>() // className → package

        for (file in files) {
            if (!file.path.endsWith(".kt") || !file.content.contains("@Entity")) continue
            val pkgMatch = Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE).find(file.content)
            val pkg = pkgMatch?.groupValues?.get(1) ?: continue
            val classMatch = Regex("""(?:data\s+)?class\s+(\w+)""").find(file.content) ?: continue
            val className = classMatch.groupValues[1]
            entityClassNames[className] = pkg

            // @ManyToOne 필드 찾기
            val manyToOneRegex = Regex("""@ManyToOne[^)]*\)?\s*(?:@[^\n]*\n\s*)*(?:var|val)\s+(\w+)\s*:\s*(\w+)""")
            for (match in manyToOneRegex.findAll(file.content)) {
                val fieldName = match.groupValues[1]
                val parentType = match.groupValues[2]
                relations.add(ManyToOneRel(className, parentType, fieldName, pkg))
            }
        }

        // 2. 어떤 코드에서 entity.collectionName 형태로 컬렉션을 참조하는지 확인
        // parentEntity에서 어떤 @OneToMany 컬렉션이 이미 있는지 확인
        val existingCollections = mutableMapOf<String, MutableSet<String>>() // entityClass → set of collection field names
        for (file in files) {
            if (!file.path.endsWith(".kt") || !file.content.contains("@Entity")) continue
            val classMatch = Regex("""(?:data\s+)?class\s+(\w+)""").find(file.content) ?: continue
            val className = classMatch.groupValues[1]
            val oneToManyRegex = Regex("""@OneToMany[^)]*\)?\s*(?:@[^\n]*\n\s*)*(?:var|val)\s+(\w+)""")
            for (match in oneToManyRegex.findAll(file.content)) {
                existingCollections.getOrPut(className) { mutableSetOf() }.add(match.groupValues[1])
            }
        }

        // 3. 각 @ManyToOne에 대해 역방향 @OneToMany가 필요한지 확인
        data class NeededCollection(val parentClass: String, val collectionName: String, val childClass: String, val mappedBy: String, val childPkg: String)
        val neededCollections = mutableListOf<NeededCollection>()

        for (rel in relations) {
            // 컬렉션 이름 추론: ChildEntity → childEntities (camelCase + 's')
            // 컬렉션 이름 후보: 1) "supplierProducts" 2) "products" (부모 이름 접두어 제거)
            val fullCollName = rel.childClass.replaceFirstChar { it.lowercase() } + "s"
            val shortCollName = rel.childClass.removePrefix(rel.parentClass).replaceFirstChar { it.lowercase() } + "s"
            val candidateNames = listOf(fullCollName, shortCollName).distinct()

            val existing = existingCollections[rel.parentClass] ?: emptySet()
            if (candidateNames.any { existing.contains(it) }) continue

            // 다른 코드에서 이 컬렉션이 참조되는지 확인 (후보 이름 중 하나라도)
            var usedCollName: String? = null
            for (candidate in candidateNames) {
                val referenced = files.any { file ->
                    file.path.endsWith(".kt") && !file.content.contains("@Entity") &&
                    file.content.contains(".$candidate")
                }
                if (referenced) {
                    usedCollName = candidate
                    break
                }
            }

            if (usedCollName != null) {
                neededCollections.add(NeededCollection(rel.parentClass, usedCollName, rel.childClass, rel.fieldName, rel.childPkg))
            }
        }

        if (neededCollections.isEmpty()) return files to changes

        // 4. 부모 Entity에 @OneToMany 추가
        val updatedFiles = files.map { file ->
            if (!file.path.endsWith(".kt") || !file.content.contains("@Entity")) return@map file
            val classMatch = Regex("""(?:data\s+)?class\s+(\w+)""").find(file.content) ?: return@map file
            val className = classMatch.groupValues[1]

            val toAdd = neededCollections.filter { it.parentClass == className }
            if (toAdd.isEmpty()) return@map file

            var content = file.content
            for (needed in toAdd) {
                // data class인 경우: constructor 닫는 ')' 뒤에 body { } 추가
                // 이미 body가 있으면 body 안에 추가
                val oneToManyField = "    @OneToMany(mappedBy = \"${needed.mappedBy}\", fetch = FetchType.LAZY)\n" +
                    "    var ${needed.collectionName}: MutableList<${needed.childClass}> = mutableListOf()"

                // 이미 body { } 가 있는지 확인
                val bodyRegex = Regex("""\)\s*\{""")
                if (bodyRegex.containsMatchIn(content)) {
                    // 기존 body에 추가
                    val insertPos = bodyRegex.find(content)!!.range.last + 1
                    content = content.substring(0, insertPos) + "\n" + oneToManyField + "\n" + content.substring(insertPos)
                } else {
                    // ) 뒤에 body 추가
                    val lastParen = content.lastIndexOf(')')
                    if (lastParen >= 0) {
                        content = content.substring(0, lastParen + 1) + " {\n" + oneToManyField + "\n}" + content.substring(lastParen + 1)
                    }
                }

                // import 추가 (필요시)
                val entityPkg = entityClassNames[className] ?: ""
                if (entityPkg != needed.childPkg && !content.contains("import ${needed.childPkg}.${needed.childClass}")) {
                    val importLine = "import ${needed.childPkg}.${needed.childClass}"
                    val lastImport = content.lastIndexOf("import ")
                    if (lastImport >= 0) {
                        val insertAt = content.indexOf('\n', lastImport)
                        if (insertAt >= 0) {
                            content = content.substring(0, insertAt) + "\n$importLine" + content.substring(insertAt)
                        }
                    }
                }

                changes.add(IntegrityChange(file.path, "MissingOneToMany",
                    "Added @OneToMany collection '${needed.collectionName}' for reverse of ${needed.childClass}.${needed.mappedBy}", true))
            }

            GeneratedFile(file.path, content)
        }

        return updatedFiles to changes
    }

    // ── Check 32: build.gradle.kts 중복 의존성 제거 + 버전 없는 의존성 보정 ──

    private fun checkDuplicateGradleDependencies(files: List<GeneratedFile>): Pair<List<GeneratedFile>, List<IntegrityChange>> {
        val changes = mutableListOf<IntegrityChange>()
        val gradleFile = files.find { it.path.endsWith("build.gradle.kts") } ?: return files to changes

        val lines = gradleFile.content.lines().toMutableList()
        var modified = false

        // 의존성 라인 파싱: configuration("group:artifact:version") or configuration("group:artifact")
        data class DepInfo(val lineIndex: Int, val config: String, val group: String, val artifact: String, val version: String?)

        val depRegex = Regex("""^\s*(implementation|runtimeOnly|testImplementation|api|compileOnly)\("([^"]+)"\)\s*$""")
        val deps = mutableListOf<DepInfo>()

        for (i in lines.indices) {
            val match = depRegex.find(lines[i]) ?: continue
            val config = match.groupValues[1]
            val coord = match.groupValues[2]
            val parts = coord.split(":")
            if (parts.size < 2) continue
            val group = parts[0]
            val artifact = parts[1]
            val version = if (parts.size >= 3 && parts[2].isNotBlank()) parts[2] else null
            deps.add(DepInfo(i, config, group, artifact, version))
        }

        // group:artifact 별 그룹화
        val grouped = deps.groupBy { "${it.group}:${it.artifact}" }
        val linesToRemove = mutableSetOf<Int>()

        for ((key, entries) in grouped) {
            if (entries.size <= 1) {
                // 단일 항목이지만 버전 없는 경우 보정
                val entry = entries[0]
                if (entry.version == null) {
                    val knownVersion = findKnownVersion(entry.group, entry.artifact)
                    if (knownVersion != null) {
                        val oldLine = lines[entry.lineIndex]
                        lines[entry.lineIndex] = oldLine.replace(
                            "\"${entry.group}:${entry.artifact}\"",
                            "\"${entry.group}:${entry.artifact}:$knownVersion\""
                        )
                        modified = true
                        changes.add(IntegrityChange(gradleFile.path, "DuplicateGradleDependency",
                            "Added missing version to $key → $knownVersion", true))
                    }
                }
                continue
            }

            // 중복 있음: 버전 있는 것을 우선으로 유지, 나머지 제거
            val withVersion = entries.filter { it.version != null }
            val toKeep = if (withVersion.isNotEmpty()) {
                // 버전 있는 것 중 마지막 것을 유지
                withVersion.last()
            } else {
                entries.last()
            }

            for (entry in entries) {
                if (entry.lineIndex != toKeep.lineIndex) {
                    linesToRemove.add(entry.lineIndex)
                    modified = true
                    changes.add(IntegrityChange(gradleFile.path, "DuplicateGradleDependency",
                        "Removed duplicate dependency: ${entry.config}(\"${entry.group}:${entry.artifact}${entry.version?.let { ":$it" } ?: ""}\")", true))
                }
            }
        }

        if (!modified) return files to changes

        // 삭제 대상 라인 제거 (역순으로)
        val newLines = lines.filterIndexed { index, _ -> index !in linesToRemove }
        val newContent = newLines.joinToString("\n")
        val updatedFiles = files.map { if (it.path == gradleFile.path) GeneratedFile(it.path, newContent) else it }
        return updatedFiles to changes
    }

    private fun findKnownVersion(group: String, artifact: String): String? {
        // Spring Boot BOM이 관리하는 의존성은 버전 불필요 — 하지만 명시적 지정이 안전
        val known = mapOf(
            "io.jsonwebtoken:jjwt-api" to "0.12.6",
            "io.jsonwebtoken:jjwt-impl" to "0.12.6",
            "io.jsonwebtoken:jjwt-jackson" to "0.12.6",
        )
        return known["$group:$artifact"]
    }

    // ── Check 36: LLM이 남긴 잔여 백슬래시 제거 ──

    private fun checkStrayBackslash(files: List<GeneratedFile>): Pair<List<GeneratedFile>, List<IntegrityChange>> {
        val changes = mutableListOf<IntegrityChange>()
        val updatedFiles = files.map { file ->
            if (!file.path.endsWith(".kt")) return@map file

            var content = file.content
            var modified = false

            // \@ → @ (@ 앞의 불필요한 백슬래시)
            if (content.contains("\\@")) {
                content = content.replace("\\@", "@")
                modified = true
                changes.add(IntegrityChange(file.path, "StrayBackslash",
                    "Removed stray backslash before @", true))
            }

            // )\ + 공백 + 코드 → )\n + 공백 + 코드 (줄바꿈이 백슬래시로 잘못됨)
            val strayLineBreak = Regex("""(\))\\\s{2,}(return|val|var|if|for|while|when|throw)""")
            if (strayLineBreak.containsMatchIn(content)) {
                content = strayLineBreak.replace(content) { m ->
                    val indent = m.value.substringAfter("\\").substringBefore(m.groupValues[2])
                    "${m.groupValues[1]}\n$indent${m.groupValues[2]}"
                }
                modified = true
                changes.add(IntegrityChange(file.path, "StrayBackslash",
                    "Fixed stray backslash as line break", true))
            }

            if (modified) GeneratedFile(file.path, content) else file
        }
        return updatedFiles to changes
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

        // Debug: Repository 관련 선언 추적
        val repoDecls = allDecls.filter { it.name.contains("Repository") }
        if (repoDecls.isNotEmpty()) {
            log.info("[INTEGRITY] DuplicateClassRemoval debug: {} Repository declarations: {}",
                repoDecls.size, repoDecls.map { "${it.packageName}.${it.name} @ ${it.file.path}" })
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

    // ── Check 24: 테스트 파일 무결성 ──

    private fun checkTestFileIntegrity(files: List<GeneratedFile>): Pair<List<GeneratedFile>, List<IntegrityChange>> {
        val changes = mutableListOf<IntegrityChange>()
        val testFiles = files.filter { it.path.contains("src/test/") && it.path.endsWith(".kt") }
        if (testFiles.isEmpty()) return files to changes

        // 1. build.gradle.kts에 테스트 의존성 추가
        val gradleFile = files.find { it.path.endsWith("build.gradle.kts") }
        var updatedFiles = files
        if (gradleFile != null && testFiles.isNotEmpty()) {
            var gradleContent = gradleFile.content
            var gradleModified = false

            // mockito 의존성
            if (testFiles.any { it.content.contains("mock(") || it.content.contains("`when`") || it.content.contains("Mockito") }
                && !gradleContent.contains("mockito")) {
                val depInsertPoint = gradleContent.lastIndexOf("}")
                val depsBlock = gradleContent.indexOf("dependencies")
                if (depsBlock >= 0 && depInsertPoint > depsBlock) {
                    // dependencies 블록의 마지막 } 직전에 추가
                    val lastDepEnd = gradleContent.lastIndexOf("\n", depInsertPoint - 1)
                    if (lastDepEnd >= 0) {
                        gradleContent = gradleContent.substring(0, lastDepEnd) +
                                "\n    testImplementation(\"org.mockito:mockito-core:5.11.0\")" +
                                "\n    testImplementation(\"org.mockito.kotlin:mockito-kotlin:5.2.1\")" +
                                gradleContent.substring(lastDepEnd)
                        gradleModified = true
                        changes.add(IntegrityChange(gradleFile.path, "TestFileIntegrity",
                            "Added testImplementation for mockito-core and mockito-kotlin", true))
                    }
                }
            }

            // spring-boot-starter-test 의존성
            if (!gradleContent.contains("spring-boot-starter-test")) {
                val depInsertPoint = gradleContent.lastIndexOf("}")
                val depsBlock = gradleContent.indexOf("dependencies")
                if (depsBlock >= 0 && depInsertPoint > depsBlock) {
                    val lastDepEnd = gradleContent.lastIndexOf("\n", depInsertPoint - 1)
                    if (lastDepEnd >= 0) {
                        gradleContent = gradleContent.substring(0, lastDepEnd) +
                                "\n    testImplementation(\"org.springframework.boot:spring-boot-starter-test\")" +
                                gradleContent.substring(lastDepEnd)
                        gradleModified = true
                        changes.add(IntegrityChange(gradleFile.path, "TestFileIntegrity",
                            "Added testImplementation for spring-boot-starter-test", true))
                    }
                }
            }

            if (gradleModified) {
                updatedFiles = updatedFiles.map {
                    if (it.path == gradleFile.path) GeneratedFile(it.path, gradleContent) else it
                }
            }
        }

        // 2. Entity 생성자 파라미터 수 수집 (검증용)
        data class EntityCtorInfo(val paramCount: Int, val paramNames: List<String>)
        val entityCtorMap = mutableMapOf<String, EntityCtorInfo>()
        for (file in updatedFiles) {
            if (!file.path.endsWith(".kt") || !file.content.contains("@Entity")) continue
            val classMatch = Regex("""(?:data\s+)?class\s+(\w+)\s*\(([^)]*)\)""", RegexOption.DOT_MATCHES_ALL)
                .find(file.content) ?: continue
            val entityName = classMatch.groupValues[1]
            val paramsStr = classMatch.groupValues[2]
            val paramNames = Regex("""(?:var|val)\s+(\w+)\s*:""").findAll(paramsStr).map { it.groupValues[1] }.toList()
            entityCtorMap[entityName] = EntityCtorInfo(paramNames.size, paramNames)
        }

        // 3. 테스트 파일 검증 — 생성자 인자 불일치 또는 심각한 import 누락 감지 시 제거
        val testFilesToRemove = mutableSetOf<String>()
        for (testFile in testFiles) {
            var hasIssue = false

            // Entity 생성자 호출 검증: EntityName(arg1, arg2, ...) — 인자 수가 맞는지
            for ((entityName, ctorInfo) in entityCtorMap) {
                val ctorCallPattern = Regex("""$entityName\s*\(([^)]+)\)""")
                for (callMatch in ctorCallPattern.findAll(testFile.content)) {
                    val argsStr = callMatch.groupValues[1]
                    // named parameter가 아닌 positional args 카운트
                    if (argsStr.contains("=")) continue  // named params는 OK
                    val argCount = argsStr.split(",").size
                    if (argCount != ctorInfo.paramCount && argCount != ctorInfo.paramCount - ctorInfo.paramNames.count { n ->
                            // default value가 있는 파라미터 수 추정 (id, isActive 등)
                            n == "id" || n.startsWith("is") || n.endsWith("At")
                        }) {
                        hasIssue = true
                        break
                    }
                }
                if (hasIssue) break
            }

            // mock() 사용하지만 import 없음 — import 추가로 해결 가능하므로 제거 대상 아님
            // EntityNotFoundException 참조하지만 import 없음 — 마찬가지

            if (hasIssue) {
                testFilesToRemove.add(testFile.path)
                changes.add(IntegrityChange(testFile.path, "TestFileIntegrity",
                    "Removed test file with entity constructor argument mismatch", true))
            }
        }

        // 4. 남은 테스트 파일에 누락된 import 추가
        updatedFiles = updatedFiles.map { file ->
            if (!file.path.contains("src/test/") || !file.path.endsWith(".kt")) return@map file
            if (file.path in testFilesToRemove) return@map file

            var content = file.content
            var modified = false

            // Mockito imports
            if (content.contains("mock(") && !content.contains("import org.mockito")) {
                val packageEnd = content.indexOf('\n')
                if (packageEnd >= 0) {
                    content = content.substring(0, packageEnd) +
                            "\nimport org.mockito.Mockito.mock" +
                            "\nimport org.mockito.Mockito.`when`" +
                            "\nimport org.mockito.Mockito.verify" +
                            content.substring(packageEnd)
                    modified = true
                    changes.add(IntegrityChange(file.path, "TestFileIntegrity",
                        "Added Mockito imports", true))
                }
            }

            // EntityNotFoundException import
            if (content.contains("EntityNotFoundException") && !content.contains("import jakarta.persistence.EntityNotFoundException")
                && !content.contains("import javax.persistence.EntityNotFoundException")) {
                val packageEnd = content.indexOf('\n')
                if (packageEnd >= 0) {
                    content = content.substring(0, packageEnd) +
                            "\nimport jakarta.persistence.EntityNotFoundException" +
                            content.substring(packageEnd)
                    modified = true
                    changes.add(IntegrityChange(file.path, "TestFileIntegrity",
                        "Added EntityNotFoundException import", true))
                }
            }

            if (modified) GeneratedFile(file.path, content) else file
        }

        // 5. 깨진 테스트 파일 제거
        if (testFilesToRemove.isNotEmpty()) {
            updatedFiles = updatedFiles.filter { it.path !in testFilesToRemove }
        }

        return updatedFiles to changes
    }

    // ── Check 30: 패키지-디렉토리 경로 불일치 수정 ──
    // LLM이 src/main/kotlin/entity/Supplier.kt 경로로 생성했지만
    // 파일 내 package가 com.skytree.skystock.entity인 경우, 경로를
    // src/main/kotlin/com/skytree/skystock/entity/Supplier.kt 로 수정한다.

    private fun checkPackagePathAlignment(files: List<GeneratedFile>): Pair<List<GeneratedFile>, List<IntegrityChange>> {
        val changes = mutableListOf<IntegrityChange>()
        val updatedFiles = files.map { file ->
            if (!file.path.endsWith(".kt")) return@map file

            // package 선언 추출
            val packageMatch = Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE).find(file.content)
                ?: return@map file
            val packageName = packageMatch.groupValues[1]

            // 기대 경로 계산: src/main/kotlin/ + package를 /로 변환 + FileName.kt
            val srcPrefix = "src/main/kotlin/"
            val expectedPackagePath = packageName.replace('.', '/')
            val fileName = file.path.substringAfterLast('/')

            // 현재 경로에서 src/main/kotlin/ 이후 부분 추출
            val currentAfterSrc = if (file.path.contains(srcPrefix)) {
                file.path.substringAfter(srcPrefix).substringBeforeLast('/')
            } else {
                return@map file // src/main/kotlin/ 기반이 아니면 스킵
            }

            // 패키지 경로와 디렉토리 경로가 이미 일치하면 스킵
            if (currentAfterSrc == expectedPackagePath) return@map file

            val newPath = "${srcPrefix}${expectedPackagePath}/${fileName}"
            changes.add(IntegrityChange(file.path, "PackagePathAlignment",
                "Fixed path mismatch: ${file.path} → $newPath (package $packageName)", true))

            GeneratedFile(newPath, file.content)
        }
        return updatedFiles to changes
    }

    // ── Check 31: 프로젝트 내부 클래스 import 자동 추가 ──
    // Service/Controller가 다른 파일에 선언된 Entity, DTO 등을 사용하지만 import가 없는 경우 자동 추가.

    private fun checkProjectInternalImports(files: List<GeneratedFile>): Pair<List<GeneratedFile>, List<IntegrityChange>> {
        val changes = mutableListOf<IntegrityChange>()

        // 1. 프로젝트 내 모든 클래스 선언 수집: className → packageName
        val classToPackage = mutableMapOf<String, String>()
        for (file in files) {
            if (!file.path.endsWith(".kt")) continue
            val packageMatch = Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE).find(file.content) ?: continue
            val pkg = packageMatch.groupValues[1]
            // class, data class, interface, enum class, object 선언
            val classPattern = Regex("""(?:data\s+)?(?:class|interface|enum\s+class|object)\s+(\w+)""")
            for (m in classPattern.findAll(file.content)) {
                val className = m.groupValues[1]
                if (className.length > 1 && className[0].isUpperCase()) {
                    classToPackage[className] = pkg
                }
            }
        }

        // 2. 각 파일에서 미 import 참조 찾아 import 추가
        val updatedFiles = files.map { file ->
            if (!file.path.endsWith(".kt")) return@map file

            val lines = file.content.lines()
            val existingImports = lines
                .filter { it.trimStart().startsWith("import ") }
                .map { it.trim().removePrefix("import ").trim() }
                .toSet()

            val packageMatch = Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE).find(file.content)
            val filePackage = packageMatch?.groupValues?.get(1) ?: ""

            val lastImportIdx = lines.indexOfLast { it.trimStart().startsWith("import ") }
            val bodyText = if (lastImportIdx >= 0) {
                lines.subList(lastImportIdx + 1, lines.size).joinToString("\n")
            } else {
                file.content
            }

            val importsToAdd = mutableListOf<String>()
            for ((className, pkg) in classToPackage) {
                // 같은 패키지면 import 불필요
                if (pkg == filePackage) continue

                val fqn = "$pkg.$className"
                if (fqn in existingImports) continue
                // wildcard import로 커버되는 경우
                if ("$pkg.*" in existingImports) continue

                // 코드 본문에서 사용되는지 확인 (단어 경계, import 라인 제외)
                val pattern = Regex("\\b${Regex.escape(className)}\\b")
                if (pattern.containsMatchIn(bodyText)) {
                    importsToAdd.add(fqn)
                    changes.add(IntegrityChange(file.path, "ProjectInternalImport",
                        "Added project-internal import: $fqn", true))
                }
            }

            if (importsToAdd.isEmpty()) return@map file

            val newLines = lines.toMutableList()
            val insertIdx = if (lastImportIdx >= 0) lastImportIdx + 1 else {
                val pkgIdx = newLines.indexOfFirst { it.trimStart().startsWith("package ") }
                if (pkgIdx >= 0) pkgIdx + 1 else 0
            }
            for ((i, imp) in importsToAdd.sorted().withIndex()) {
                newLines.add(insertIdx + i, "import $imp")
            }

            GeneratedFile(file.path, newLines.joinToString("\n"))
        }

        return updatedFiles to changes
    }
}
