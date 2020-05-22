package com.Wingsanjyu.plugin

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import net.mamoe.mirai.console.plugins.ConfigSection
import net.mamoe.mirai.contact.Contact
import net.mamoe.mirai.message.data.toMessage
import net.mamoe.mirai.utils.ExternalImage
import net.mamoe.mirai.utils.toExternalImage
import net.mamoe.mirai.utils.upload
import org.jsoup.Jsoup
import com.Wingsanjyu.plugin.HsoPro.Image_Resize_Max_Width_Height
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam


data class FetchedImage(
        private val url:String,
        private val src:ExternalImage,
        private val tags:String,
        private val cacheFile: File
){
    private fun String.doTemplateReplacement():String{
        return this.replace("{url}",url).replace("{tags}","神秘链接：")
    }

    suspend fun send(
            subject:Contact
    ){
        val template = HsoPro.messageTemplate
        if(!template.contains("{image}")){
            subject.sendMessage(template.doTemplateReplacement().toMessage())
        }else {
            with(template.split("{image}")) {
                subject.sendMessage(this[0].doTemplateReplacement().toMessage() + src.upload(subject) + this[1].doTemplateReplacement())
            }
        }
        cacheFile.delete()
    }
}


class ImageFetcher(
        private val sourceSet: List<ConfigSection>,
        size: Int,
        workerSize: Int
){
    private val channel = Channel<FetchedImage>(size)

    private val cacheFolder by lazy {
        File(HsoPro.dataFolder.absolutePath + "/cache").also{
            it.mkdir()
        }.absolutePath  + "/"
    }

    suspend fun get(): FetchedImage{
        return channel.receive()
    }

    init {
        repeat(workerSize){
            HsoPro.launch {
                while (isActive) {
                    try {
                        val source = sourceSet.random()

                        var type:String

                        val url = if (Image_Resize_Max_Width_Height > 1200) {
                            source.getString("url").also {
                                type = it.substringAfterLast(".")
                            }
                        } else {
                            type = "jpg"
                            with(source.getString("url").replace("img-original", "img-master")) {
                                this.substringBeforeLast(".") + "_master1200.jpg"
                            }
                        }


                        val imageBodyStream = withTimeoutOrNull(20 * 1000) {
                            withContext(Dispatchers.IO) {
                                Jsoup
                                        .connect(url)
                                        .followRedirects(true)
                                        .timeout(180_000)
                                        .ignoreContentType(true)
                                        .userAgent("Mozilla/5.0 (Macintosh; U; Intel Mac OS X 10_6_7; ja-jp) AppleWebKit/533.20.25 (KHTML, like Gecko) Version/5.0.4 Safari/533.20.27")
                                        .referrer(
                                                "https://www.pixiv.net/member_illust.php?mode=medium&illust_id=" + source.getString("pid")
                                        )
                                        .ignoreHttpErrors(true)
                                        .maxBodySize(100000000)
                                        .execute().also {
                                            check(it.statusCode() == 200) {
                                                "Failed to download image"
                                            }
                                        }
                            }?.bodyStream() ?: error("Failed to download image")
                        }

                        var image = withContext(Dispatchers.IO) {
                            ImageIO.read(imageBodyStream)
                        }


                        if (Image_Resize_Max_Width_Height > 1200 && image.width.coerceAtLeast(image.height) > Image_Resize_Max_Width_Height) {
                            //compress
                            val rate = (Image_Resize_Max_Width_Height.toFloat() / image.width.coerceAtLeast(image.height))
                            val newWidth = (image.width * rate).toInt()
                            val newHeight = (image.height * rate).toInt()
                            image = withContext(Dispatchers.IO) {
                                val newImg = BufferedImage(newWidth, newHeight, image.type)
                                val g = newImg.createGraphics()
                                val rh = RenderingHints(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                                g.drawImage(image, 0, 0, newWidth, newHeight, 0, 0, image.width, image.height, null)
                                g.dispose()
                                newImg
                            }
                        }

                        if(HsoPro.Anti_Detect){
                            image.setRGB(1, 1, 0xFFFFFF)
                            image.setRGB(1, 2, 0xFFFFFF)
                            image.setRGB(2, 1, 0xFFFFFF)
                        }

                        val fetchedImage = withContext(Dispatchers.IO) {
                            val cacheFile = File(cacheFolder + source.getString("uid") + "." + type).also {
                                it.deleteOnExit()
                            }
                            when(type) {
                                "png" -> {
                                    ImageIO.write(image, "png", cacheFile)
                                }
                                "jpg" -> {
                                    val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
                                    val param = writer.defaultWriteParam
                                    param.compressionMode = ImageWriteParam.MODE_EXPLICIT
                                    param.compressionQuality = 1.0f
                                    val iioImage = IIOImage(image, null, null)
                                    writer.output = ImageIO.createImageOutputStream(cacheFile)
                                    writer.write(null,iioImage,param)
                                }
                                else -> {
                                    try{
                                        ImageIO.write(image, "png", cacheFile)
                                    }catch (e: Throwable){
                                        HsoPro.logger.info("unknown Image type $type")
                                    }
                                }
                            }
                            FetchedImage(
                                    source.getString("url"),
                                    cacheFile.toExternalImage(),
                                    source.getString("tags"),
                                    cacheFile
                            )
                        }

                        channel.send(fetchedImage)

                    }catch (e:Throwable){
                        if(e is CancellationException){
                            HsoPro.logger.info("Image Fetcher (worker) has closed")
                        }else {
                            HsoPro.logger.info("Image Fetcher got error")
                            HsoPro.logger.error(e)
                            HsoPro.logger.info(e.localizedMessage)
                        }
                    }
                }
            }
        }
    }


}