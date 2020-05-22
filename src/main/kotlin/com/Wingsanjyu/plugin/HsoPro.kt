package com.Wingsanjyu.plugin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.mamoe.mirai.console.command.ContactCommandSender
import net.mamoe.mirai.console.command.registerCommand
import net.mamoe.mirai.console.plugins.Config
import net.mamoe.mirai.console.plugins.PluginBase
import net.mamoe.mirai.console.plugins.withDefaultWriteSave
import net.mamoe.mirai.contact.Group
import net.mamoe.mirai.contact.isAdministrator
import net.mamoe.mirai.contact.isOperator
import net.mamoe.mirai.event.subscribeGroupMessages
import net.mamoe.mirai.message.data.*
import net.mamoe.mirai.utils.toExternalImage
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.RenderedImage
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.contracts.contract

object HsoPro : PluginBase() {

    lateinit var images: Config
    lateinit var normalImageProvider: ImageFetcher
    lateinit var r18ImageProvider: ImageFetcher

    private val config = loadConfig("setting.yml")

    val Normal_Image_Trigger by config.withDefaultWriteSave { "setu" }
    val R18_Image_Trigger by config.withDefaultWriteSave { "nsfw" }
    var Image_Resize_Max_Width_Height by config.withDefaultWriteSave { 1200 }
    var Anti_Detect by config.withDefaultWriteSave { false }
    var CacheSize   by config.withDefaultWriteSave { 10 }
    var WorkerSize   by config.withDefaultWriteSave { 2 }
    var messageTemplate by config.withDefaultWriteSave { "{image}\n{tags}\n{url}" }

    val groupsAllowNormal by lazy {
        config.setIfAbsent("Allow_Normal_Image_Groups", listOf<Long>())
        config.getLongList("Allow_Normal_Image_Groups").toMutableList()
    }
    val groupsAllowR18 by lazy {
        config.setIfAbsent("Allow_R18_Image_Groups", listOf<Long>())
        config.getLongList("Allow_R18_Image_Groups").toMutableList()
    }

    private val rootFolder by lazy {
        File(dataFolder.absolutePath).also{
            it.mkdir()
        }.absolutePath  + "/"
    }
    private lateinit var triggerImage : BufferedImage
    override fun onLoad() {
        try {
            images = getResourcesConfig("data.yml")
        } catch (e: Exception) {
            e.printStackTrace()
            logger.info("无法加载本地图片")
        }
    }

    override fun onEnable() {
        logger.info("本地图片版本" + images.getString("version"))
        val r18 = images.getConfigSectionList("R18")
        val normal = images.getConfigSectionList("normal")
        logger.info("Normal * " + normal.size)
        logger.info("R18    * " + r18.size)
        normalImageProvider = ImageFetcher(
                normal, CacheSize, WorkerSize
        )
        r18ImageProvider = ImageFetcher(
                r18, CacheSize, WorkerSize
        )
        val cacheFile = File(rootFolder + "pic.jpg")
        try{
            triggerImage = ImageIO.read(cacheFile)
        }catch (e: Throwable){
            HsoPro.logger.info("unknown Image type jpg")
        }
        registerCommand {
            name = "hso"
            alias = listOf("setu")
            description = "hso[色图]插件总指令"
            usage = "[/hso normal]     允许本群发送普通图片\n" +
                    "[/hso r18]        允许本群发送r18图片\n" +
                    "更多设置请在插件配置文件中更改"
            onCommand {
                val operatingGroup = if (this is ContactCommandSender && this.contact is Group) {
                    this.contact.id
                } else {
                    0
                }
                if (it.isEmpty()) {
                    return@onCommand false
                }
                when (it[0].toLowerCase()) {
                    "normal" -> {
                        if (operatingGroup == 0L) {
                            return@onCommand false
                        }
                        HsoPro.groupsAllowNormal.add(operatingGroup)
                        this.sendMessage("以允许" + operatingGroup + "发送普通的图")
                    }
                    "r18"    -> {
                        HsoPro.groupsAllowR18.add(operatingGroup)
                        this.sendMessage("以允许" + operatingGroup + "发送R18的图")
                    }
                    else -> {
                        return@onCommand false
                    }
                }
                return@onCommand true
            }
        }

        subscribeGroupMessages {
            (contains(Normal_Image_Trigger)) {
                if (groupsAllowNormal.contains(this.group.id)) {
                    launch {
                        normalImageProvider.get().send(subject)
                    }
                }
            }
            (contains(R18_Image_Trigger)) {
                if (groupsAllowR18.contains(this.group.id)) {
                    launch {
                        r18ImageProvider.get().send(subject)
                    }
                }
            }
            (contains("/hso normal")){
                if (this.sender.isOperator()) {
                    HsoPro.groupsAllowNormal.add(this.group.id)
                    subject.sendMessage("以允许" + this.group.id + "发送普通的图")
                }
                else
                {
                    subject.sendMessage("需要管理员及以上的权限")
                }
            }
            (contains("/hso unnormal")){
                if (this.sender.isOperator()) {
                    HsoPro.groupsAllowNormal.remove(this.group.id)
                    subject.sendMessage("不允许" + this.group.id + "发送普通的图")
                }
                else
                {
                    subject.sendMessage("需要管理员及以上的权限")
                }
            }
            (contains("/hso r18")){
                if (this.sender.isOperator()) {
                    HsoPro.groupsAllowNormal.add(this.group.id)
                    subject.sendMessage("以允许" + this.group.id + "发送R18的图")
                }
                else
                {
                    subject.sendMessage("需要管理员及以上的权限")
                }
            }
            (contains("/hso unr18")){
                if (this.sender.isOperator()) {
                    HsoPro.groupsAllowNormal.remove(this.group.id)
                    subject.sendMessage("不允许" + this.group.id + "发送R18的图")
                }
                else
                {
                    subject.sendMessage("需要管理员及以上的权限")
                }
            }
            (contains("/hso help")){
                subject.sendMessage("[/hso normal]     允许本群发送普通图片\n" +
                        "[/hso r18]        允许本群发送r18图片\n" +
                        "[/hso unnormal]        不允许本群发送普通图片\n" +
                        "[/hso unr18]        不允许本群发送r18图片\n" +
                        "更多设置请在插件配置文件中更改")
            }
//            this.always {
//                if(message.get(Image.Key) != null){
//                    val getImage = message.get(Image.Key)!!
//                    if (getImage.md5 == triggerImage)
//                }
//            }
        }
        logger.info("hso插件已加载")
    }

    override fun onDisable() {
        super.onDisable()
        logger.info("Saving data")
        config["Allow_Normal_Image_Groups"] = groupsAllowNormal
        config["Allow_R18_Image_Groups"] = groupsAllowR18
        config.save()
    }
}