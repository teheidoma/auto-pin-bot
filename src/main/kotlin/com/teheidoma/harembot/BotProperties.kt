package com.teheidoma.harembot

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConstructorBinding

@ConstructorBinding
@ConfigurationProperties("bot")
data class BotProperties(
    val token:String,
    val webhookName:String,
    val channelName:String,
    val webhookUrl:String
)
