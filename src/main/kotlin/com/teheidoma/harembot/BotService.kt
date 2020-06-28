package com.teheidoma.harembot

import com.fasterxml.jackson.annotation.JsonProperty
import discord4j.core.DiscordClient
import discord4j.core.GatewayDiscordClient
import discord4j.core.`object`.entity.Attachment
import discord4j.core.`object`.entity.Guild
import discord4j.core.`object`.entity.Member
import discord4j.core.`object`.entity.Message
import discord4j.core.`object`.entity.Webhook
import discord4j.core.`object`.entity.channel.TextChannel
import discord4j.core.event.domain.channel.PinsUpdateEvent
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
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

        client.onDisconnect().block()
    }

    fun handlePins(event: PinsUpdateEvent) {
        event.channel
            .flatMapMany { it.pinnedMessages }
            .filter { it != null }
            .sort(Comparator.comparing(Message::getId))
            .flatMap { message ->
                message.guild
                    .flatMap { getOrCreateChannel(it) }
                    .flatMap { getOrCreateWebhook(it) }
                    .zipWith(message.authorAsMember)
                    .flatMap { zip -> sendWebhook(zip, message)}
                    .flatMap {
                        if(it.statusCode==HttpStatus.OK) {
                            logger.info("successfully pinned message {}", message.id.asLong())
                            message.unpin()
                        }else{
                            Mono.error(RuntimeException("${message.id.asLong()} ${it.statusCodeValue}" +
                                                            " ${it.body} ${it.headers}"))
                        }
                    }
            }
            .subscribe()
    }

    fun sendWebhook(zip:Tuple2<Webhook, Member>, message: Message):Mono<ResponseEntity<String>> {
        val (webhook, member) = zip

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
            .next()
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
            .cast(TextChannel::class.java)
            .next()
            .switchIfEmpty(guild.createTextChannel {
                it.setName(properties.channelName)
            })
    }

    data class WebhookExecute(
        val content: String,
        val username: String,
        @JsonProperty("avatar_url")
        val avatarUrl: String,
        val embeds: List<Map<String, Map<String, String>>> = emptyList()
    )
}

operator fun <T, U> Tuple2<T, U>.component1() = t1
operator fun <T, U> Tuple2<T, U>.component2() = t2
