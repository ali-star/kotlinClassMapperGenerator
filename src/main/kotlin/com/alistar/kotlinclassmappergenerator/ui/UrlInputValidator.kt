package com.alistar.kotlinclassmappergenerator.ui

import com.intellij.openapi.ui.InputValidator
import java.net.MalformedURLException
import java.net.URL

object UrlInputValidator : InputValidator {
    override fun checkInput(inputString: String): Boolean = try {
        URL(inputString)
        true
    } catch (e: MalformedURLException) {
        false
    }

    override fun canClose(inputString: String): Boolean = true
}
