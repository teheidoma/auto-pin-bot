package com.teheidoma.harembot.commands

import discord4j.core.`object`.entity.Message
import reactor.core.publisher.Mono

interface TextCommand {
    fun isApplicable(message: Message): Boolean

    fun execute(message: Message): Mono<Void>
}
