package com.teheidoma.harembot

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import java.io.File


@SpringBootApplication
@EnableConfigurationProperties(BotProperties::class)
class AutoPinBotApplication

fun main(args: Array<String>) {
    File("data").mkdirs()
    runApplication<AutoPinBotApplication>(*args)
}
