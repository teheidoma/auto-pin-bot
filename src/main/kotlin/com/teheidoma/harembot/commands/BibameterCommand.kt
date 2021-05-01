package com.teheidoma.harembot.commands

import discord4j.core.`object`.entity.Message
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import kotlin.random.Random

@Component
class BibameterCommand : TextCommand {
    override fun isApplicable(message: Message): Boolean {
        return message.content.matches(Regex.fromLiteral("!bibametr"))
    }

    override fun execute(message: Message): Mono<Void> {
        return message.author.map { user ->
            message.channel
                    .flatMap {
                        it.createMessage {
                            it.setContent("Твоя биба ${getBiba(user.username)} см чел")
                        }
                    }
                    .then()
        }.orElse(Mono.empty());
    }

    private fun getBiba(username: String): Int {
        val random = Random(username.hashCode())
        return random.nextInt(3, 50)
    }
}
