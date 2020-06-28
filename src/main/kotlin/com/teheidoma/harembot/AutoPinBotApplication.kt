package com.teheidoma.harembot

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication


@SpringBootApplication
@EnableConfigurationProperties(BotProperties::class)
class AutoPinBotApplication

fun main(args: Array<String>) {
    runApplication<AutoPinBotApplication>(*args)
}
