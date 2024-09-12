package com.alistar.kotlinclassmappergenerator

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFileFactory
import org.jetbrains.kotlin.idea.base.utils.fqname.fqName
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.psiUtil.isPrivate
import org.jetbrains.kotlin.resolve.ImportPath
import org.jetbrains.kotlin.types.isNullable
import org.jetbrains.kotlin.types.typeUtil.isEnum

class MapperGeneratorV3 {

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

            if (ktClass.isEnum()) {
                val expressionText = buildString {
                    append("when(this) {")
                    append("\n")
                    ktClass.declarations.map {
                        append(ktClass.name + "." + it.name + " -> " + ktClass.name + classSuffix + "." + it.name)
                        append("\n")
                    }
                    append("}")
                }
                val function = psiFactory.createFunction(
                    "fun $ktClassName.mapTo$classSuffix(): $mappedKtClassName = $expressionText"
                )
                file.add(function)
            } else {
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
                                className = parameterInfo.ktClassName,
                                classSuffix = classSuffix,
                                packageName = packageName,
                                directory = directory,
                                project = project,
                            )
                            arguments["${parameter.name}$classSuffix"] =
                                parameterInfo.name + (if (parameterInfo.type.isNullable()) "?" else "") + ".mapTo$classSuffix()"
                        } else if (parameterInfo.ktClass.isEnum()) {
                            arguments["${parameter.name}$classSuffix"] = buildString {
                                append("when(${parameter.name}) {")
                                append("\n")
                                parameterInfo.ktClass.declarations.map {
                                    append(
                                        parameterInfo.ktClass.name + "." + it.name + " -> "
                                                + parameterInfo.ktClass.name + classSuffix + "." + it.name
                                    )
                                    append("\n")
                                }
                                append("}")
                            }
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
            }
            file.reformat()
        }
    }
}
