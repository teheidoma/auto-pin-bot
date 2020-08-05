package com.teheidoma.harembot

import org.springframework.data.jpa.repository.JpaRepository
import javax.persistence.Entity
import javax.persistence.Enumerated
import javax.persistence.GeneratedValue
import javax.persistence.GenerationType
import javax.persistence.Id
import javax.persistence.Table

@Entity
@Table
data class GuildEntity(
    @Id
    val id: Long,

    val name: String,

    val adminId: Long,

    @Enumerated
    val displayType: DisplayType = DisplayType.EMBED,

    val color: Int = 0xf3862c
)

interface GuildRepository : JpaRepository<GuildEntity, Long>
