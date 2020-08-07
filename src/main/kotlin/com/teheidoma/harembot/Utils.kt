package com.teheidoma.harembot

import java.util.Properties

fun getVersion(): String{
    AutoPinBotApplication::class.java.classLoader.getResourceAsStream("version.properties").use {
        val properties = Properties()
        properties.load(it)

        return properties.getProperty("version")
    }
}
