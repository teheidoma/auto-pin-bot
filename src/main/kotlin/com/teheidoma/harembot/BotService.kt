package com.teheidoma.harembot

import com.fasterxml.jackson.annotation.JsonProperty
import discord4j.core.DiscordClient
import discord4j.core.GatewayDiscordClient
import discord4j.core.`object`.entity.Attachment
import discord4j.core.`object`.entity.Guild
import discord4j.core.`object`.entity.Member
import discord4j.core.`object`.entity.Message
import discord4j.core.`object`.entity.Webhook
import discord4j.core.`object`.entity.channel.GuildMessageChannel
import discord4j.core.`object`.entity.channel.TextChannel
import discord4j.core.event.domain.channel.PinsUpdateEvent
import discord4j.core.event.domain.guild.GuildCreateEvent
import discord4j.core.event.domain.message.MessageCreateEvent
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.util.function.Tuple2

@Service
class BotService(private val properties: BotProperties) {
    private val logger = LoggerFactory.getLogger("main")

    private val rest = RestTemplate()

    private val client: GatewayDiscordClient

    init {
        val discord = DiscordClient.create(properties.token)

        client = discord.login().block()!!

        client.eventDispatcher.on(PinsUpdateEvent::class.java)
            .subscribe(::handlePins)

        client.eventDispatcher.on(MessageCreateEvent::class.java)
            .filter { it.message.author.map { !it.isBot }.orElse(false) }
            .filter { it.message.content == "!pin import" }
            .flatMap { it.message.channel.zipWith(it.guild) }
            .flatMap {
                val (channel, guild) = it
                pinMessages(channel.pinnedMessages, Mono.just(guild))
            }
            .subscribe()

        client.eventDispatcher.on(GuildCreateEvent::class.java)
            .flatMap { getOrCreateChannel(it.guild) }
            .flatMap { getOrCreateWebhook(it) }
            .subscribe()

        client.onDisconnect().block()
    }

    fun handlePins(event: PinsUpdateEvent) {
        event.channel
            .cast(GuildMessageChannel::class.java)
            .flatMapMany { it.pinnedMessages }
            .sort(Comparator.comparing(Message::getId))
            .takeLast(1)
            .flatMap { pinMessages(Flux.just(it), it.guild) }
            .subscribe()
    }

    fun pinMessages(messages: Flux<Message>, guild: Mono<Guild>): Flux<Void> {
        return messages
            .filter { it != null }
            .sort(Comparator.comparing(Message::getId))
            .zipWith(
                guild.flatMap { getOrCreateChannel(it) }
                    .flatMap { getOrCreateWebhook(it) }
            )
            .flatMap {
                val (message, webhook) = it
                message.authorAsMember
                    .flatMap { member ->
                        sendWebhook(webhook, member, message)
                    }.flatMap {
                        if (it.statusCode == HttpStatus.NO_CONTENT) {
                            logger.info("successfully pinned message {}", message.id.asLong())
                            message.unpin()
                        } else {
                            Mono.error(RuntimeException("${message.id.asLong()} ${it.statusCodeValue}" +
                                                            " ${it.body} ${it.headers}"))
                        }
                    }
            }
    }

    fun sendWebhook(webhook: Webhook, member: Member, message: Message): Mono<ResponseEntity<String>> {
        val webhookExecute = WebhookExecute(message.content,
                                            member.username,
                                            member.avatarUrl,
                                            createEmbeds(message.attachments))

        return Mono.fromSupplier {
            rest.postForEntity(properties.webhookUrl.format(webhook.id.asLong(), webhook.token),
                               webhookExecute,
                               String::class.java)
        }
    }

    fun getOrCreateWebhook(channel: TextChannel): Mono<Webhook> {
        return channel.webhooks
            .filter { it.name.isPresent && it.name.get() == properties.webhookName }
            .singleOrEmpty()
            .switchIfEmpty(client.self
                               .flatMap { it.avatar }
                               .flatMap { img ->
                                   channel.createWebhook {
                                       it.setName(properties.webhookName)
                                       it.setAvatar(img)
                                   }
                               })
    }

    fun createEmbeds(attachments: Set<Attachment>): List<Map<String, Map<String, String>>> {
        if (attachments.isEmpty()) return emptyList()
        return listOf(
            mapOf(
                "image" to mapOf(
                    "url" to attachments.first().url
                )
            )
        )
    }

    fun getOrCreateChannel(guild: Guild): Mono<TextChannel> {
        return guild.channels
            .filter { it.name == properties.channelName }
            .log()
            .cast(TextChannel::class.java)
            .singleOrEmpty()
            .switchIfEmpty(
                guild
                    .createTextChannel {
                        it.setName(properties.channelName)
                    }
                    .flatMap { channel ->
                        channel.createMessage {
                            it.setContent("send `!pinbot import` to channel, from which you want to import message")
                        }.map {
                            channel
                        }
                    }
            )
    }

    data class WebhookExecute(
        val content: String,
        val username: String,
        //hack to json deserialize
        val avatar_url: String,
        val embeds: List<Map<String, Map<String, String>>> = emptyList()
    )
}

operator fun <T, U> Tuple2<T, U>.component1() = t1
operator fun <T, U> Tuple2<T, U>.component2() = t2
