package com.teheidoma.harembot.commands

import discord4j.core.`object`.entity.Message
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextAware
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import javax.annotation.PostConstruct

@Component
class TextCommandFacade : ApplicationContextAware {
    private lateinit var context: ApplicationContext

    private val commands: MutableList<TextCommand> = arrayListOf()

    @PostConstruct
    fun init() {
        commands.addAll(context.getBeansOfType(TextCommand::class.java).values)
    }

    fun execute(message: Message): Mono<Void> {
        val command = commands.firstOrNull {
            it.isApplicable(message)
        }

        return command?.execute(message) ?: Mono.empty();
    }

    override fun setApplicationContext(applicationContext: ApplicationContext) {
        this.context = applicationContext
    }
}
