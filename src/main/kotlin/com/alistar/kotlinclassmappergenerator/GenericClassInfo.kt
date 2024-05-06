package com.alistar.kotlinclassmappergenerator

import org.jetbrains.kotlin.psi.KtImportDirective

data class GenericClassInfo(
    val args: List<String>,
    val imports: List<KtImportDirective>
)
