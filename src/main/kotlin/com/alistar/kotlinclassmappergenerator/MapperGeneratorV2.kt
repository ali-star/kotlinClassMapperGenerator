package com.alistar.kotlinclassmappergenerator

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.search.ProjectScope
import org.jetbrains.kotlin.asJava.classes.KtUltraLightClass
import org.jetbrains.kotlin.idea.base.utils.fqname.fqName
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.isPrivate
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.ImportPath
import org.jetbrains.kotlin.types.TypeProjection
import org.jetbrains.kotlin.types.isNullable
import org.jetbrains.kotlin.types.typeUtil.*
import org.jetbrains.kotlin.util.isAnnotated
import org.jetbrains.kotlinx.serialization.compiler.backend.common.serialName

class MapperGeneratorV2 {

    fun generateClass(
        ktClass: KtClass,
        parentKtClass: KtClass? = null,
        className: String,
        classSuffix: String,
        packageName: String,
        directory: PsiDirectory,
        project: Project,
    ) {
        val psiFactory = KtPsiFactory(project = project, markGenerated = true)
        val psiFileFactory = PsiFileFactory.getInstance(project)

        WriteCommandAction.runWriteCommandAction(project) {
            // If it has a parent class it means that the class is nested and the model for this
            // class needs to be generated inside the ktClass
            if (parentKtClass != null) {
                val (dataClass, imports) = ktClass(
                    psiFactory = psiFactory,
                    className = className,
                    classSuffix = classSuffix,
                    ktClass = ktClass,
                    project = project,
                    packageName = packageName,
                    directory = directory,
                )
                val file = parentKtClass.containingKtFile.importList
                imports.forEach { import ->
                    if (file?.imports?.find { it.text.toString() == import.text.toString() } == null) {
                        file?.add(import)
                    }
                }
                parentKtClass.addDeclaration(dataClass)
            } else {
                val modelFile = psiFileFactory.createFileFromText(
                    "$className$classSuffix.kt",
                    KotlinFileType(),
                    "package $packageName"
                )

                val primaryConstructorParameters = ktClass.primaryConstructorParameters

                val file = directory.add(modelFile) as KtFile
                addImports(ktClass.containingKtFile, file, primaryConstructorParameters)

                val (dataClass, imports) = ktClass(
                    psiFactory = psiFactory,
                    className = className,
                    classSuffix = classSuffix,
                    ktClass = ktClass,
                    project = project,
                    packageName = packageName,
                    directory = directory,
                )
                imports.forEach { import ->
                    if (file.importList?.imports?.find { it.text.toString() == import.text.toString() } == null) {
                        file.importList?.add(import)
                    }
                }
                file.add(dataClass)

                file.reformat()
            }
        }
        val documentManager: PsiDocumentManager = PsiDocumentManager.getInstance(project)
        documentManager.commitAllDocuments()
    }

    private fun addImports(
        ktFile: KtFile,
        newFile: KtFile,
        primaryConstructorParameters: List<KtParameter>
    ) {
        val imports = ktFile.importList?.imports
        imports?.forEach { import ->
            val text = import.text
            var importIsAnnotation = false
            primaryConstructorParameters.forEach parametersLoop@{ parameter ->
                if (parameter.isAnnotated) {
                    parameter.annotationEntries.forEach { annotation ->
                        val name = annotation.shortName?.asString()
                        if (text.endsWith(name ?: "---")) {
                            importIsAnnotation = true
                            return@parametersLoop
                        }
                    }
                }
            }
            if (!importIsAnnotation) {
                newFile.importList?.add(import)
            }
        }
    }

    private fun ktClass(
        psiFactory: KtPsiFactory,
        className: String,
        classSuffix: String,
        ktClass: KtClass,
        project: Project,
        packageName: String,
        directory: PsiDirectory
    ): Pair<KtClass, List<KtImportDirective>> {
        val imports = ArrayList<KtImportDirective>()
        val dataClass = psiFactory.createClass("data class $className$classSuffix")
        val constructor = dataClass.createPrimaryConstructorParameterListIfAbsent()

        val primaryConstructorParameters = ktClass.primaryConstructorParameters

        primaryConstructorParameters.forEach parametersLoop@{ parameter ->
            if (parameter.isPrivate()) {
                return@parametersLoop
            }

            // region possible improvement
            val typeReference = parameter.typeReference
            val bindingContext = typeReference?.analyze()
            val type = bindingContext?.get(BindingContext.TYPE, typeReference)
            val aSerialName = type?.serialName()

            val newPsiClass = JavaPsiFacade.getInstance(project)
                .findClass(aSerialName!!, ProjectScope.getAllScope(project))

            val ktUltraLightClass = newPsiClass as? KtUltraLightClass
            val nestedClass = ktUltraLightClass?.kotlinOrigin as? KtClass
            val nestedClassName = nestedClass?.fqName?.shortName()?.asString() ?: ""
            // endregion

            val typeArguments = type.arguments

            val newParameter: KtParameter

            if (nestedClass != null) {
                if (nestedClass.isData()) {
                    generateClass(
                        ktClass = nestedClass,
                        parentKtClass = if (nestedClass.containingKtFile == ktClass.containingKtFile) dataClass else null,
                        className = nestedClassName,
                        classSuffix = classSuffix,
                        packageName = packageName,
                        directory = directory,
                        project = project,
                    )

                    val typeText = type.fqName?.shortName()?.asString()
                        ?.replace("?", "") + classSuffix + if (type.isNullable()) "?" else ""
                    val text = "val ${parameter.name}$classSuffix: $typeText"
                    newParameter = psiFactory.createParameter(
                        text = text
                    )
                } else {
                    newParameter = psiFactory.createParameter(
                        text = "val ${parameter.name}: /* Implement manually */"
                    )
                }
            } else if (typeArguments.isNotEmpty()) {
                val args = ArrayList<String>()
                typeArguments.forEach typeArgumentsLoop@{ typeArgument ->
                    val typeArgumentSerialName = typeArgument.type.serialName()

                    val typeArgumentNewPsiClass = JavaPsiFacade.getInstance(project)
                        .findClass(typeArgumentSerialName, ProjectScope.getAllScope(project))

                    val typeArgumentKtUltraLightClass = typeArgumentNewPsiClass as? KtUltraLightClass
                    val typeArgumentNestedClass = typeArgumentKtUltraLightClass?.kotlinOrigin as? KtClass
                    val typeArgumentNestedClassName = typeArgumentNestedClass?.fqName?.shortName()?.asString() ?: ""

                    if (typeArgumentNestedClass == null) {
                        val typeText = typeArgument.type.fqName?.asString() ?: ""
                        val isBasicType = with(typeArgument.type) {
                            isInt() || isLong() || isShort() || isByte() || isFloat() || isDouble() || isChar()
                                    || isBoolean() || isAny() || fqName?.asString() == "kotlin.String"
                        }
                        if (typeText.replace("?", "").contains(".") && !isBasicType) {
                            imports.add(psiFactory.createImportDirective(ImportPath.fromString(typeText)))
                        }
                        args.add(typeText.substringAfterLast("."))
                        return@typeArgumentsLoop
                    } else {
                        if (typeArgumentNestedClass.isData()) {
                            val typeText =
                                typeArgumentNestedClassName + classSuffix + if (typeArgument.type.isNullable()) "?" else ""
                            if (typeText.replace("?", "").contains(".")) {
                                imports.add(psiFactory.createImportDirective(ImportPath.fromString(typeText)))
                            }
                            args.add(typeText)
                        } else {
                            args.add("/* Implement manually */")
                            return@typeArgumentsLoop
                        }
                    }

                    generateClass(
                        ktClass = typeArgumentNestedClass,
                        parentKtClass = if (typeArgumentNestedClass.containingKtFile == ktClass.containingKtFile) dataClass else null,
                        className = typeArgumentNestedClassName,
                        classSuffix = classSuffix,
                        packageName = packageName,
                        directory = directory,
                        project = project,
                    )
                }
                val argsString = args.joinToString { it }
                val typeText = type.fqName?.asString()
                if (typeText?.replace("?", "")?.contains(".") == true) {
                    imports.add(psiFactory.createImportDirective(ImportPath.fromString(typeText)))
                }
                newParameter = psiFactory.createParameter(
                    text = "val ${parameter.name}: ${typeText?.substringAfterLast(".")}<$argsString>${if (type.isNullable()) "?" else ""}"
                )
            } else {
                newParameter = psiFactory.createParameter(
                    text = "val ${parameter.name}: $type"
                )
            }

            constructor.addParameter(newParameter)
        }
        return dataClass to imports
    }

    fun generateMapper(
        ktClass: KtClass,
        rootFile: KtFile? = null,
        className: String,
        classSuffix: String,
        packageName: String,
        directory: PsiDirectory,
        project: Project,
    ) {
        val psiFactory = KtPsiFactory(project = project, markGenerated = true)
        val psiFileFactory = PsiFileFactory.getInstance(project)

        WriteCommandAction.runWriteCommandAction(project) {
            val modelFile = psiFileFactory.createFileFromText(
                "$className${classSuffix}Mapper.kt",
                KotlinFileType(),
                "package $packageName"
            )

            val primaryConstructorParameters = ktClass.primaryConstructorParameters

            val file = rootFile ?: (directory.findFile(modelFile.name) ?: directory.add(modelFile)) as KtFile

            // The package name of a kt class could be different from the current directory's package name
            val (ktClassName, mappedKtClassName) = getClassNameInfo(ktClass, packageName, classSuffix, file, psiFactory)

            val arguments = HashMap<String, String>()

            primaryConstructorParameters.forEach parametersLoop@{ parameter ->
                if (parameter.isPrivate()) {
                    return@parametersLoop
                }

                // region possible improvement
                val name = parameter.fqName?.shortName()?.asString()
                val typeReference = parameter.typeReference
                val bindingContext = typeReference?.analyze()
                val type = bindingContext?.get(BindingContext.TYPE, typeReference)
                val aSerialName = type?.serialName()

                val newPsiClass = JavaPsiFacade.getInstance(project)
                    .findClass(aSerialName!!, ProjectScope.getAllScope(project))

                val ktUltraLightClass = newPsiClass as? KtUltraLightClass
                val nestedClass = ktUltraLightClass?.kotlinOrigin as? KtClass
                val nestedClassName = nestedClass?.fqName?.shortName()?.asString() ?: ""
                // endregion

                val typeIsCollection = with(type) {
                    fqName?.asString() == Collection::class.qualifiedName || type.supertypes().find {
                        it.fqName?.asString() == Collection::class.qualifiedName
                    } != null
                }

                val typeIsMap = with(type) {
                    fqName?.asString() == Map::class.qualifiedName || type.supertypes().find {
                        it.fqName?.asString() == Map::class.qualifiedName
                    } != null
                }

                val typeIsPair = with(type) {
                    fqName?.asString() == Pair::class.qualifiedName || type.supertypes().find {
                        it.fqName?.asString() == Pair::class.qualifiedName
                    } != null
                }

                val typeArguments = type.arguments

                if (nestedClass != null) {
                    if (nestedClass.isData()) {
                        val parent = (nestedClass.parent as? KtClassBody)?.parent as? KtClass
                        generateMapper(
                            ktClass = nestedClass,
                            rootFile = if (parent != null) file else null,
                            className = nestedClassName,
                            classSuffix = classSuffix,
                            packageName = packageName,
                            directory = directory,
                            project = project,
                        )
                        arguments["${parameter.name}$classSuffix"] =
                            name + (if (type.isNullable()) "?" else "") + ".mapTo$classSuffix()"
                    } else {
                        arguments["${parameter.name}"] = "/* Implement manually */"
                    }
                } else if (typeIsCollection) {
                    val typeArgument = typeArguments.first()
                    val (typeArgumentNestedClass, typeArgumentNestedClassName) = getTypeInfo(typeArgument, project)

                    if (typeArgumentNestedClass != null && typeArgumentNestedClass.isData()) {
                        val parent = (typeArgumentNestedClass.parent as? KtClassBody)?.parent as? KtClass
                        generateMapper(
                            ktClass = typeArgumentNestedClass,
                            rootFile = if (parent != null) file else null,
                            className = typeArgumentNestedClassName,
                            classSuffix = classSuffix,
                            packageName = packageName,
                            directory = directory,
                            project = project,
                        )
                        arguments["${parameter.name}"] =
                            name + (if (type.isNullable()) "?" else "") + ".map { it.mapTo$classSuffix() }"
                    } else {
                        arguments[parameter.name.toString()] = parameter.name.toString()
                    }
                } else if (typeIsMap) {
                    val key = typeArguments[0]
                    val value = typeArguments[1]
                    val (keyNestedClass, keyClassName) = getTypeInfo(key, project)
                    val (valueNestedClass, valueClassName) = getTypeInfo(value, project)

                    if (keyNestedClass != null) {
                        val parent = (keyNestedClass.parent as? KtClassBody)?.parent as? KtClass
                        generateMapper(
                            ktClass = keyNestedClass,
                            rootFile = if (parent != null) file else null,
                            className = keyClassName,
                            classSuffix = classSuffix,
                            packageName = packageName,
                            directory = directory,
                            project = project,
                        )
                    }

                    if (valueNestedClass != null) {
                        val parent = (valueNestedClass.parent as? KtClassBody)?.parent as? KtClass
                        generateMapper(
                            ktClass = valueNestedClass,
                            rootFile = if (parent != null) file else null,
                            className = valueClassName,
                            classSuffix = classSuffix,
                            packageName = packageName,
                            directory = directory,
                            project = project,
                        )
                    }

                    val expression = buildString {
                        append("HashMap")
                        append("<")
                        if (keyNestedClass != null) {
                            val (_, keyMappedClassName) = getClassNameInfo(
                                ktClass = keyNestedClass,
                                packageName = keyNestedClass.containingKtFile.packageFqName.asString(),
                                classSuffix = classSuffix,
                                file = file,
                                psiFactory = psiFactory,
                            )
                            append(keyMappedClassName)
                        } else {
                            append(key.type.fqName?.asString())
                        }
                        append(", ")
                        if (valueNestedClass != null) {
                            val (_, valueMappedClassName) = getClassNameInfo(
                                ktClass = valueNestedClass,
                                packageName = valueNestedClass.containingKtFile.packageFqName.asString(),
                                classSuffix = classSuffix,
                                file = file,
                                psiFactory = psiFactory,
                            )
                            append(valueMappedClassName)
                        } else {
                            append(value.type.fqName?.asString())
                        }
                        append(">")
                        append("().apply {")
                        append(name + (if (type.isNullable()) "?" else "") + ".forEach {\n")
                        if (keyNestedClass != null) {
                            append("it.key.mapTo$classSuffix()")
                        } else {
                            append("it.key")
                        }
                        append(" to ")
                        if (valueNestedClass != null) {
                            append("it.value.mapTo$classSuffix()")
                        } else {
                            append("it.value")
                        }
                        append("\n}")
                        append("\n}")
                    }
                    arguments["${parameter.name}"] = expression
                } else if (typeIsPair) {
                    val first = typeArguments[0]
                    val second = typeArguments[1]
                    val (firstNestedClass, firstClassName) = getTypeInfo(first, project)
                    val (secondNestedClass, secondClassName) = getTypeInfo(second, project)

                    if (firstNestedClass != null) {
                        val parent = (firstNestedClass.parent as? KtClassBody)?.parent as? KtClass
                        generateMapper(
                            ktClass = firstNestedClass,
                            rootFile = if (parent != null) file else null,
                            className = firstClassName,
                            classSuffix = classSuffix,
                            packageName = packageName,
                            directory = directory,
                            project = project,
                        )
                    }

                    if (secondNestedClass != null) {
                        val parent = (secondNestedClass.parent as? KtClassBody)?.parent as? KtClass
                        generateMapper(
                            ktClass = secondNestedClass,
                            rootFile = if (parent != null) file else null,
                            className = secondClassName,
                            classSuffix = classSuffix,
                            packageName = packageName,
                            directory = directory,
                            project = project,
                        )
                    }

                    val expression = buildString {
                        append(name + (if (type.isNullable()) "?" else "") + ".let {\n")
                        if (firstNestedClass != null) {
                            append("it.first.mapTo$classSuffix()")
                        } else {
                            append("it.first")
                        }
                        append(" to ")
                        if (secondNestedClass != null) {
                            append("it.second.mapTo$classSuffix()")
                        } else {
                            append("it.second")
                        }
                        append("\n}")
                    }
                    arguments["${parameter.name}"] = expression
                } else {
                    arguments[parameter.name.toString()] = parameter.name.toString()
                }
            }

            val expressionText = "$mappedKtClassName(${
                arguments.entries.joinToString(separator = "\n") { (paramName, paramValue) ->
                    "$paramName = $paramValue,"
                }
            })"

            val function = psiFactory.createFunction(
                "fun $ktClassName.mapTo$classSuffix():" +
                        " $mappedKtClassName = $expressionText"
            )

            file.add(function)
            file.reformat()
        }
    }

    private fun getClassNameInfo(
        ktClass: KtClass,
        packageName: String,
        classSuffix: String,
        file: KtFile,
        psiFactory: KtPsiFactory,
    ): Pair<String?, String?> {
        val ktClassPackageName = ktClass.containingKtFile.packageFqName.asString()

        val ktClassName = if (ktClassPackageName != packageName) {
            ktClass.fqName?.asString()
        } else {
            ktClass.fqName?.asString()?.replace("$packageName.", "") ?: ""
        }

        ktClassName.takeIf { !it.isNullOrEmpty() && it.contains(".") }?.let {
            file.importList?.add(psiFactory.createImportDirective(ImportPath.fromString(it)))
        }
        val mappedKtClassName = ktClassName
            ?.replace("$ktClassPackageName.", "")
            ?.replace(".", "$classSuffix.")?.let {
                "$packageName.$it$classSuffix"
            }
        val mappedKtClassName1 = ktClassName
            ?.replace("$ktClassPackageName.", "")
            ?.replace(".", "$classSuffix.")?.let {
                "$it$classSuffix"
            }
        mappedKtClassName.takeIf { !it.isNullOrEmpty() && it.contains(".") }?.let {
            file.importList?.add(psiFactory.createImportDirective(ImportPath.fromString(it)))
        }
        return Pair(ktClass.fqName?.asString()?.substringAfterLast("."), mappedKtClassName1)
    }

    private fun getTypeInfo(
        typeArgument: TypeProjection,
        project: Project
    ): Pair<KtClass?, String> {
        val typeArgumentSerialName = typeArgument.type.serialName()

        val typeArgumentNewPsiClass = JavaPsiFacade.getInstance(project)
            .findClass(typeArgumentSerialName, ProjectScope.getAllScope(project))

        val typeArgumentKtUltraLightClass = typeArgumentNewPsiClass as? KtUltraLightClass
        val typeArgumentNestedClass = typeArgumentKtUltraLightClass?.kotlinOrigin as? KtClass
        val typeArgumentNestedClassName = typeArgumentNestedClass?.fqName?.shortName()?.asString() ?: ""
        return Pair(typeArgumentNestedClass, typeArgumentNestedClassName)
    }

    private fun PsiElement.reformat() {
        val ktFile = containingFile as KtFile
        removeUnusedImportDirectives(ktFile)
        CodeStyleManager.getInstance(project).reformat(this)
    }

    private fun removeUnusedImportDirectives(file: KtFile) {
        val importDirectives = file.importDirectives
        val unusedImportDirectives = importDirectives.filter { it.isUsedImportDirective(file).not() }
        unusedImportDirectives.forEach { it.delete() }
    }

    private fun KtImportDirective.isUsedImportDirective(file: KtFile): Boolean {
        if (importedFqName?.asString()?.endsWith("*") == true) return true

        val fileText = file.text
        val importShortName = importedFqName?.shortName()?.asString()
        val isUsedAsParameter = fileText.contains(": $importShortName,")
                || fileText.contains(": $importShortName?,")
        val isUsed = fileText.contains("$importShortName.")
        return isUsedAsParameter || isUsed
    }
}
