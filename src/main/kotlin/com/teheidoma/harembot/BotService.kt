package com.teheidoma.harembot

import com.teheidoma.harembot.commands.TextCommandFacade
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
import reactor.util.function.Tuple3
import reactor.util.function.Tuple4
import reactor.util.function.Tuple5
import reactor.util.function.Tuples
import reactor.util.function.component1
import reactor.util.function.component2
import reactor.util.function.component3
import reactor.util.function.component4
import java.awt.Color

@Service
class BotService(
        private val properties: BotProperties,
        private val guildRepository: GuildRepository,
        private val textCommandFacade: TextCommandFacade
) {
    private val logger = LoggerFactory.getLogger("main")
    private val rest = RestTemplate()

    private lateinit var client: GatewayDiscordClient

    init {
        val discord = DiscordClient.create(properties.token)

        discord.withGateway {
            client = it
            Mono.`when`(
                    it.on(PinsUpdateEvent::class.java).flatMap(this::handlePins),
                    it.on(MessageCreateEvent::class.java).flatMap(this::handleMessages),
                    it.on(GuildCreateEvent::class.java).flatMap(this::handleGuildCreate)
            )
        }.block()
    }

    fun handleGuildCreate(event: GuildCreateEvent): Mono<Webhook> {
        logger.info("loaded server \"{}\"", event.guild.name)
        return getOrCreateChannel(event.guild)
                .flatMap { getOrCreateWebhook(it) }
    }

    fun handleMessages(event: MessageCreateEvent): Mono<Void> {
        return event.message.channel
                .cast(TextChannel::class.java)
                .flatMap { textCommandFacade.execute(event.message) }
    }

    fun handlePins(event: PinsUpdateEvent): Flux<Void> {
        return event.channel
                .cast(GuildMessageChannel::class.java)
                .flatMapMany { it.pinnedMessages }
                .sort(Comparator.comparing(Message::getId))
                .takeLast(1)
                .flatMap { pinMessages(Flux.just(it), it.guild) }
    }

    fun pinMessages(messages: Flux<Message>, guild: Mono<Guild>): Flux<Void> {
        return messages
                .filter { it != null }
                .sort(Comparator.comparing(Message::getId))
                .zipWith(guild)
                .flatMap { tuple ->
                    getOrCreateChannel(tuple.t2)
                            .zipWhen { getOrCreateWebhook(it) }
                            .zipWhen({ getOrCreateGuildEntity(tuple.t2) }, { e1, e2 -> e1.append(e2) })
                            .map { it.append(tuple.t1) }
                            .doOnError(::println)
                }
                .flatMap {
                    val (textChannel,
                            webhook,
                            guildEntity,
                            message) = it

                    when (guildEntity.displayType) {
                        DisplayType.WEBHOOK -> message.authorAsMember
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
                        DisplayType.EMBED -> message.authorAsMember
                                .zipWith(message.channel.cast(TextChannel::class.java))
                                .flatMap { zip ->
                                    val (member, srcChannel) = zip
                                    if (message.attachments.size > 0 && message.attachments.first().url.endsWith("mp4")) {
                                        textChannel.createMessage {
                                            it.setContent(message.attachments.first().url)
                                        }
                                    } else {
                                        textChannel.createMessage { msg ->
                                            println("create")
                                            msg.setEmbed {
                                                it.setColor(Color(guildEntity.color))
                                                it.setAuthor(member.displayName, null, member.avatarUrl)
                                                it.setTimestamp(message.timestamp)
                                                it.setTitle(message.content)
                                                if (message.attachments.isNotEmpty()) {
                                                    it.setImage(message.attachments.first().url)
                                                }
                                                it.setDescription("#${srcChannel.name}")
                                            }
                                        }
                                    }
                                }
                                .flatMap { message.unpin() }
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

    fun getOrCreateGuildEntity(guild: Guild): Mono<GuildEntity> {
        return Mono.fromSupplier {
            guildRepository.findById(guild.id.asLong())
                    .orElseGet {
                        guildRepository.save(GuildEntity(guild.id.asLong(),
                                guild.name,
                                guild.ownerId.asLong()))
                    }
        }
    }

    data class WebhookExecute(
            val content: String,
            val username: String,
            //hack to json deserialize
            val avatar_url: String,
            val embeds: List<Map<String, Map<String, String>>> = emptyList()
    )
}

fun <T, U, X> Tuple2<T, U>.append(obj: X): Tuple3<T, U, X> {
    return Tuples.of(t1, t2, obj)
}

fun <T, U, X, Z> Tuple3<T, U, X>.append(obj: Z): Tuple4<T, U, X, Z> {
    return Tuples.of(t1, t2, t3, obj)
}

fun <A, B, C, D, E> Tuple4<A, B, C, D>.append(obj: E): Tuple5<A, B, C, D, E> {
    return Tuples.of(t1, t2, t3, t4, obj)
}
