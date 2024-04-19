package com.alistar.kotlinclassmappergenerator

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFileFactory
import org.jetbrains.kotlin.idea.base.psi.kotlinFqName
import org.jetbrains.kotlin.idea.base.utils.fqname.fqName
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.isPrivate
import org.jetbrains.kotlin.resolve.ImportPath
import org.jetbrains.kotlin.types.isNullable
import org.jetbrains.kotlin.types.typeUtil.*
import org.jetbrains.kotlin.util.isAnnotated

class MapperGenerator {

    fun generateClass(
        originalKtClass: KtClass,
        newKtFile: KtFile? = null,
        parentKtClass: KtClass? = null,
        createNewClassInsidePreviousFile: Boolean,
        className: String,
        classSuffix: String,
        packageName: String,
        directory: PsiDirectory,
        project: Project,
        isRecursive: Boolean = true,
    ) {
        val psiFactory = KtPsiFactory(project = project, markGenerated = true)
        val psiFileFactory = PsiFileFactory.getInstance(project)

        WriteCommandAction.runWriteCommandAction(project) {
            // If it has a parent class it means that the class is nested and the model for this
            // class needs to be generated inside the ktClass
            if (parentKtClass != null) {
                val (dataClass, imports) = makeClass(
                    file = newKtFile!!,
                    psiFactory = psiFactory,
                    className = className,
                    classSuffix = classSuffix,
                    ktClass = originalKtClass,
                    project = project,
                    packageName = packageName,
                    directory = directory,
                )
                val file = parentKtClass.containingKtFile.importList
                imports.forEach { import ->
                    // Check if the import is already exists
                    if (file?.imports?.find { it.text.toString() == import.text.toString() } == null) {
                        file?.add(import)
                    }
                }
                parentKtClass.addDeclaration(dataClass)
            } else if (newKtFile != null && createNewClassInsidePreviousFile) {
                val (dataClass, imports) = makeClass(
                    file = newKtFile,
                    psiFactory = psiFactory,
                    className = className,
                    classSuffix = classSuffix,
                    ktClass = originalKtClass,
                    project = project,
                    packageName = packageName,
                    directory = directory,
                )
                imports.forEach { import ->
                    // Check if the import is already exists
                    if (newKtFile.importList?.imports?.find { it.text.toString() == import.text.toString() } == null) {
                        newKtFile.add(import)
                    }
                }
                newKtFile.add(dataClass)
            } else {
                val modelFile = psiFileFactory.createFileFromText(
                    "$className$classSuffix.kt",
                    KotlinFileType(),
                    "package $packageName"
                )

                val primaryConstructorParameters = originalKtClass.primaryConstructorParameters

                val file = directory.add(modelFile) as KtFile
                addImports(originalKtClass.containingKtFile, file, primaryConstructorParameters)

                val (dataClass, imports) = makeClass(
                    file = file,
                    psiFactory = psiFactory,
                    className = className,
                    classSuffix = classSuffix,
                    ktClass = originalKtClass,
                    project = project,
                    packageName = packageName,
                    directory = directory,
                    isRecursive = isRecursive,
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

    private fun generateEnum(
        ktClass: KtClass,
        parentKtClass: KtClass? = null,
        newKtFile: KtFile? = null,
        className: String,
        classSuffix: String,
        packageName: String,
        directory: PsiDirectory,
        project: Project,
        isRecursive: Boolean = true,
    ) {
        val psiFactory = KtPsiFactory(project = project, markGenerated = true)
        val psiFileFactory = PsiFileFactory.getInstance(project)

        WriteCommandAction.runWriteCommandAction(project) {
            // If it has a parent class it means that the class is nested and the model for this
            // class needs to be generated inside the ktClass
            if (parentKtClass != null) {
                val (dataClass, imports) = makeEnum(
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
                    // Check if the import is already exists
                    if (file?.imports?.find { it.text.toString() == import.text.toString() } == null) {
                        file?.add(import)
                    }
                }
                parentKtClass.addDeclaration(dataClass)
            } else if (newKtFile != null) {
                val (enumClass, imports) = makeEnum(
                    psiFactory = psiFactory,
                    className = className,
                    classSuffix = classSuffix,
                    ktClass = ktClass,
                    project = project,
                    packageName = packageName,
                    directory = directory,
                )
                imports.forEach { import ->
                    // Check if the import is already exists
                    if (newKtFile.importList?.imports?.find { it.text.toString() == import.text.toString() } == null) {
                        newKtFile.add(import)
                    }
                }
                newKtFile.add(enumClass)
            } else {
                val modelFile = psiFileFactory.createFileFromText(
                    "$className$classSuffix.kt",
                    KotlinFileType(),
                    "package $packageName"
                )

                val primaryConstructorParameters = ktClass.primaryConstructorParameters

                val file = directory.add(modelFile) as KtFile
                addImports(ktClass.containingKtFile, file, primaryConstructorParameters)

                val (dataClass, imports) = makeEnum(
                    psiFactory = psiFactory,
                    className = className,
                    classSuffix = classSuffix,
                    ktClass = ktClass,
                    project = project,
                    packageName = packageName,
                    directory = directory,
                    isRecursive = isRecursive,
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

    private fun makeEnum(
        psiFactory: KtPsiFactory,
        className: String,
        classSuffix: String,
        ktClass: KtClass,
        project: Project,
        packageName: String,
        directory: PsiDirectory,
        isRecursive: Boolean = true,
    ): Pair<KtClass, List<KtImportDirective>> {
        val imports = ArrayList<KtImportDirective>()
        val text = StringBuilder()
        text.append("enum class $className$classSuffix")
        text.append("{")
        if (ktClass.isEnum()) {
            text.append(ktClass.declarations.filterIsInstance<KtEnumEntry>().joinToString {
                if (it.hasInitializer()) {
                    "${it.name}${it.initializerList?.text}"
                } else {
                    it.name.toString()
                }
            })
        }
        text.append("}")
        val enumClass = psiFactory.createClass(text.toString())
        if (ktClass.primaryConstructorParameters.isNotEmpty()) {
            val constructor = enumClass.createPrimaryConstructorParameterListIfAbsent()
            ktClass.primaryConstructorParameters.forEach {
                constructor.addParameter(it)
            }
        }
        return enumClass to imports
    }

    private fun makeClass(
        file: KtFile,
        psiFactory: KtPsiFactory,
        className: String,
        classSuffix: String,
        ktClass: KtClass,
        project: Project,
        packageName: String,
        directory: PsiDirectory,
        isRecursive: Boolean = true,
    ): Pair<KtClass, List<KtImportDirective>> {
        val imports = ArrayList<KtImportDirective>()
        val dataClass = psiFactory.createClass("data class $className$classSuffix")
        val constructor = dataClass.createPrimaryConstructorParameterListIfAbsent()

        val primaryConstructorParameters = ktClass.primaryConstructorParameters

        if (!isRecursive) {
            imports.addAll(ktClass.containingKtFile.importList?.imports ?: emptyList())
        }

        primaryConstructorParameters.forEach parametersLoop@{ parameter ->
            if (parameter.isPrivate()) {
                return@parametersLoop
            }

            if (!isRecursive) {
                constructor.addParameter(parameter)
                return@parametersLoop
            }

            val parameterInfo = parameter.getInfo()

            val typeArguments = parameterInfo.type.arguments

            val newParameter: KtParameter

            if (parameterInfo.ktClass != null) {
                if (parameterInfo.ktClass.isData()) {
                    generateClass(
                        originalKtClass = parameterInfo.ktClass,
                        newKtFile = file,
                        parentKtClass = if (parameterInfo.ktClass.parent.kotlinFqName == ktClass.fqName) dataClass else null,
                        createNewClassInsidePreviousFile = parameterInfo.ktClass.containingKtFile.classes.size > 1,
                        className = parameterInfo.ktClassName ?: "",
                        classSuffix = classSuffix,
                        packageName = packageName,
                        directory = directory,
                        project = project,
                    )

                    val typeText = parameterInfo.type.fqName?.shortName()?.asString()
                        ?.replace("?", "") + classSuffix + if (parameterInfo.type.isNullable()) "?" else ""
                    val text = "val ${parameter.name}$classSuffix: $typeText"
                    newParameter = psiFactory.createParameter(
                        text = text
                    )
                } else if (parameterInfo.ktClass.isEnum()) {
                    generateEnum(
                        ktClass = parameterInfo.ktClass,
                        parentKtClass = if (parameterInfo.ktClass.parent.kotlinFqName == ktClass.fqName) dataClass else null,
                        newKtFile = file,
                        className = parameterInfo.ktClassName ?: "",
                        classSuffix = classSuffix,
                        packageName = packageName,
                        directory = directory,
                        project = project,
                    )

                    val typeText = parameterInfo.type.fqName?.shortName()?.asString()
                        ?.replace("?", "") + classSuffix + if (parameterInfo.type.isNullable()) "?" else ""
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
                    val (typeArgumentNestedClass, typeArgumentNestedClassName) = typeArgument.getTypeInfo(project)

                    if (typeArgumentNestedClass == null) {
                        val typeText = typeArgument.type.fqName?.asString() ?: ""
                        val isBasicType = with(typeArgument.type) {
                            isInt() || isLong() || isShort() || isByte() || isFloat() || isDouble() || isChar()
                                    || isBoolean() || isAny() || fqName?.asString() == "kotlin.String"
                        }
                        if (typeText.replace("?", "").replace("!", "").contains(".") && !isBasicType) {
                            imports.add(psiFactory.createImportDirective(ImportPath.fromString(typeText)))
                        }
                        args.add(typeText.substringAfterLast("."))
                        return@typeArgumentsLoop
                    } else {
                        if (typeArgumentNestedClass.isData()) {
                            val typeText = typeArgumentNestedClassName +
                                    classSuffix +
                                    if (typeArgument.type.isNullable()) "?" else ""
                            // Why this condition?
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
                        newKtFile = file,
                        originalKtClass = typeArgumentNestedClass,
                        parentKtClass = if (typeArgumentNestedClass.containingKtFile.parent?.kotlinFqName == ktClass.fqName)
                            dataClass else null,
                        createNewClassInsidePreviousFile = typeArgumentNestedClass.containingKtFile.classes.size > 1,
                        className = typeArgumentNestedClassName,
                        classSuffix = classSuffix,
                        packageName = packageName,
                        directory = directory,
                        project = project,
                    )
                }
                val argsString = args.joinToString { it }
                val typeText = parameterInfo.type.fqName?.asString()
                if (typeText?.replace("?", "")?.contains(".") == true) {
                    imports.add(psiFactory.createImportDirective(ImportPath.fromString(typeText)))
                }
                newParameter = psiFactory.createParameter(
                    text = "val ${parameter.name}: ${typeText?.substringAfterLast(".")}<$argsString>${if (parameterInfo.type.isNullable()) "?" else ""}"
                )
            } else {
                newParameter = psiFactory.createParameter(
                    text = "val ${parameter.name}: ${parameterInfo.type}"
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
        isRecursive: Boolean = true,
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

            val (ktClassName, mappedKtClassName) = ktClass.getClassNameInfo(
                packageName = packageName,
                classSuffix = classSuffix,
            )

            val ktClassImport = ktClass.fqName?.asString()
                ?.replace("${ktClass.containingKtFile.packageFqName.asString()}.", "")
                ?.let {
                    if (packageName != ktClass.containingKtFile.packageFqName.asString()) {
                        "${ktClass.containingKtFile.packageFqName.asString()}.$it"
                    } else {
                        // Check if the class withing the current package
                        if (it.replace("$packageName.", "").contains(".")) {
                            "$packageName.$it"
                        } else {
                            null
                        }
                    }
                }

            ktClassImport.takeIf { !it.isNullOrEmpty() && it.contains(".") }?.let {
                file.importList?.add(psiFactory.createImportDirective(ImportPath.fromString(it)))
            }

            val arguments = HashMap<String, String>()

            primaryConstructorParameters.forEach parametersLoop@{ parameter ->
                if (parameter.isPrivate()) {
                    return@parametersLoop
                }

                if (!isRecursive) {
                    arguments[parameter.name.toString()] = parameter.name.toString()
                    return@parametersLoop
                }

                val parameterInfo = parameter.getInfo()

                val typeIsCollection = parameterInfo.hasSuperType(Collection::class)
                val typeIsMap = parameterInfo.hasSuperType(Map::class)
                val typeIsPair = parameterInfo.hasSuperType(Pair::class)

                val typeArguments = parameterInfo.type.arguments

                if (parameterInfo.ktClass != null) {
                    if (parameterInfo.ktClass.isData()) {
                        val parent = (parameterInfo.ktClass.parent as? KtClassBody)?.parent as? KtClass
                        generateMapper(
                            ktClass = parameterInfo.ktClass,
                            rootFile = if (parent != null) file else null,
                            className = parameterInfo.ktClassName ?: "",
                            classSuffix = classSuffix,
                            packageName = packageName,
                            directory = directory,
                            project = project,
                        )
                        arguments["${parameter.name}$classSuffix"] =
                            parameterInfo.name + (if (parameterInfo.type.isNullable()) "?" else "") + ".mapTo$classSuffix()"
                    } else {
                        arguments["${parameter.name}"] = "/* Implement manually */"
                    }
                } else if (typeIsCollection) {
                    val typeArgument = typeArguments.first()
                    val (typeArgumentNestedClass, typeArgumentNestedClassName) = typeArgument.getTypeInfo(project)

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
                            parameter.name + (if (parameterInfo.type.isNullable()) "?" else "") + ".map { it.mapTo$classSuffix() }"
                    } else {
                        arguments[parameter.name.toString()] = parameter.name.toString()
                    }
                } else if (typeIsMap) {
                    val key = typeArguments[0]
                    val value = typeArguments[1]
                    val (keyNestedClass, keyClassName) = key.getTypeInfo(project)
                    val (valueNestedClass, valueClassName) = value.getTypeInfo(project)

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
                            val (_, keyMappedClassName) = keyNestedClass.getClassNameInfo(
                                packageName = keyNestedClass.containingKtFile.packageFqName.asString(),
                                classSuffix = classSuffix,
                            )
                            append(keyMappedClassName)
                        } else {
                            append(key.type.fqName?.asString())
                        }
                        append(", ")
                        if (valueNestedClass != null) {
                            val (_, valueMappedClassName) = valueNestedClass.getClassNameInfo(
                                packageName = valueNestedClass.containingKtFile.packageFqName.asString(),
                                classSuffix = classSuffix,
                            )
                            append(valueMappedClassName)
                        } else {
                            append(value.type.fqName?.asString())
                        }
                        append(">")
                        append("().apply {")
                        append(parameterInfo.name + (if (parameterInfo.type.isNullable()) "?" else "") + ".forEach {\n")
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
                    val (firstNestedClass, firstClassName) = first.getTypeInfo(project)
                    val (secondNestedClass, secondClassName) = second.getTypeInfo(project)

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
                        append(parameterInfo.name + (if (parameterInfo.type.isNullable()) "?" else "") + ".let {\n")
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
}
