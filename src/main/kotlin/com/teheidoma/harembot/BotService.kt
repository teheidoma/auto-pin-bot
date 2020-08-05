package com.teheidoma.harembot

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
import reactor.util.function.component5
import java.awt.Color
import javax.annotation.PostConstruct
import kotlin.random.Random

@Service
class BotService(
    private val properties: BotProperties,

    private val guildRepository: GuildRepository
) {

    private val logger = LoggerFactory.getLogger("main")

    private val rest = RestTemplate()

    private val client: GatewayDiscordClient

    init {
        val discord = DiscordClient.create(properties.token)

        client = discord.login().block()!!

        client.eventDispatcher.on(PinsUpdateEvent::class.java)
            .subscribe(::handlePins)

        client.eventDispatcher.on(MessageCreateEvent::class.java)
            .subscribe(::handleMessages)
//            .filter { it.message.author.map { !it.isBot }.orElse(false) }
//            .filter { it.message.content == "!pin import" }
//            .flatMap { it.message.channel.zipWith(it.guild) }
//            .flatMap {
//                val (channel, guild) = it
//                pinMessages(channel.pinnedMessages, Mono.just(guild))
//            }
//            .subscribe()

        client.eventDispatcher.on(GuildCreateEvent::class.java)
            .doOnEach { logger.info("loaded server \"{}\"", it.get()?.guild?.name) }
            .flatMap { getOrCreateChannel(it.guild) }
            .flatMap { getOrCreateWebhook(it) }
            .subscribe()

//        guildRepository.save(GuildEntity(5,"e", 2))
//        guildRepository.findById(5).ifPresent { println(it) }
guildRepository.findAll().forEach { println(it) }
        client.onDisconnect().block()
    }

    @PostConstruct
    fun init(){

    }

    fun handleMessages(event: MessageCreateEvent) {
        val msg = event.message.content
        val channel = event.message.channel.cast(TextChannel::class.java).block()!!
        when {
            msg.matches(Regex("pin!settings")) -> {
                event.guild
                    .flatMap { getOrCreateGuildEntity(it) }
                    .flatMap { guild ->
                        channel.createMessage {
                            it.setContent("Current color - #${Integer.toHexString(guild.color)}")
                        }
                    }
                    .subscribe()
            }
            msg.matches(Regex("pin!randomcolor")) -> {
                val color = Color(Random.nextFloat(), Random.nextFloat(), Random.nextFloat())
                channel.createMessage {
                    it.setEmbed {
                        it.setTitle("Random color")
                        it.setDescription("hex: #${Integer.toHexString(color.rgb)}\n" +
                                              "rgb: (${color.red}, ${color.green}, ${color.blue})")
                        it.setColor(color)
                    }
                }
                    .subscribe()
            }
//            msg.matches(Regex("pin!type\\W(WEBHOOK|EMBED)"))
            msg.matches(Regex("pin!color\\W[0-9a-fA-F]{6}")) -> {
                event.guild
                    .flatMap { getOrCreateGuildEntity(it) }
                    .flatMap {
                        val color = msg.substring(10)
                        val new = it.copy(color = color.toInt(16))
                        guildRepository.save(new)
                        channel.createMessage {
                            it.setContent("Color changed to #$color")
                        }
                    }
                    .subscribe()
            }
            msg.matches(Regex("pin!help")) -> {
//                channel.createMessage {
//
//                }
            }
        }
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
            .zipWith(guild)
            .flatMap { tuple ->
                val (msg, guild) = tuple

                getOrCreateChannel(guild)
                    .zipWhen { getOrCreateWebhook(it) }
                    .zipWhen({ getOrCreateGuildEntity(guild) }, {e1, e2 -> e1.append(e2)})
                    .map { it.append(msg) }
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
                            textChannel.createMessage { msg ->
                                println("create")
                                msg.setEmbed {
                                    it.setColor(Color(guildEntity.color))
                                    it.setAuthor(member.displayName, null, member.avatarUrl)
                                    it.setTimestamp(message.timestamp)
                                    it.setTitle(message.content)
                                    it.setDescription("#${srcChannel.name}")
                                }
                            }
                        }
                        .flatMap {
                            message.unpin()
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

//operator fun <T, U> Tuple2<T, U>.component1() = t1
//operator fun <T, U> Tuple2<T, U>.component2() = t2

fun <T, U, X> Tuple2<T, U>.append(obj: X): Tuple3<T, U, X> {
    return Tuples.of(t1, t2, obj)
}

fun <T, U, X, Z> Tuple3<T, U, X>.append(obj: Z): Tuple4<T, U, X, Z> {
    return Tuples.of(t1, t2, t3, obj)
}

fun <A, B, C, D, E> Tuple4<A, B, C, D>.append(obj: E): Tuple5<A, B, C, D, E> {
    return Tuples.of(t1, t2, t3, t4, obj)
}

data class GuildInfo(val guild: Guild,
                     val channel: TextChannel,
                     val guildEntity: GuildEntity,
                     val webhook: Webhook)
