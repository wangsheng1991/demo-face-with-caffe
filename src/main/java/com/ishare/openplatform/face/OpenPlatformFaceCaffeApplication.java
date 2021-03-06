package com.ishare.openplatform.face;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;


@Slf4j
@RestController
@SpringBootApplication
public class OpenPlatformFaceCaffeApplication {

    public static void main(String[] args) {
        SpringApplication.run(OpenPlatformFaceCaffeApplication.class, args);
    }

    @Value("${face.image.oss.url}")
    String IMG_OSS_URL;

    @Value("${face.cmd.classification}")
    String CMD_CLASSIFICATION;

    @Value("${face.cmd.cat.age}")
    String CMD_CAT_AGE;

    @Value("${face.cmd.cat.gender}")
    String CMD_CAT_GENDER;

    @Autowired
    RedisTemplate<String, Object> redisTemplate;

    @GetMapping("/test")
    public ResponseEntity test(@RequestParam("image") String imageName) {
        log.debug("{}", imageName);

        boolean hasKey = redisTemplate.hasKey(imageName);
        if (hasKey) {
            Object value = redisTemplate.boundValueOps(imageName).get();
            log.debug("从redis中获取 {}", value);

            return ResponseEntity.ok(value);
        } else {
            log.debug("redis中没有 {} 的结果", imageName);
            Map<String, Object> result = classification(imageName);
            redisTemplate.boundValueOps(imageName).set(result);

            return ResponseEntity.ok(result);
        }

    }

    @Async
    public Map<String, Object> classification(String image) {
        log.debug("start...");
        Map<String, Object> map = Maps.newHashMap();
        Map<String, Object> api = Maps.newHashMap();
        Process process = null;
        BufferedReader reader = null;
        try {

            //1.调用分类脚本，分类图片的年龄和性别，写入日志中
            String cmd = CMD_CLASSIFICATION.replace("#1", image).replace("#2", IMG_OSS_URL + image);
            Runtime.getRuntime().exec(cmd).waitFor();
            log.debug("exec 1 {}", cmd);

            //2.获取分类结果，年龄
            cmd = StringUtils.replace(CMD_CAT_AGE, "#", image);
            process = Runtime.getRuntime().exec(cmd);
            log.debug("exec 2 {}", cmd);
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line = null;
            List<Map<String, String>> ageLabels = Lists.newArrayList();

            int counter = 0;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("0.")) {
                    log.debug("{}", line);
                    //0.9943 - "8-13"
                    Map<String, String> row = Maps.newHashMap();
                    row.put("label", StringUtils.strip(StringUtils.substringAfter(line, "-")).replaceAll("\"", ""));
                    row.put("value", StringUtils.strip(StringUtils.substringBefore(line, "-")).replaceAll("\"", ""));
                    ageLabels.add(row);

                    if (counter == 0) {
                        api.put("gender", row.get("label"));
                    }

                    counter++;
                }
            }
            map.put("age", ageLabels);
            reader.close();

            //3.获取分类结果，性别
            cmd = StringUtils.replace(CMD_CAT_GENDER, "#", image);
            process = Runtime.getRuntime().exec(cmd);
            log.debug("exec 3 {}", cmd);
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            line = null;
            List<Map<String, String>> genderLabels = Lists.newArrayList();
            counter = 0;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("0.")) {
                    log.debug("{}", line);
                    Map<String, String> row = Maps.newHashMap();
                    row.put("value", StringUtils.strip(StringUtils.substringBefore(line, "-")).replaceAll("\"", ""));
                    row.put("label", StringUtils.strip(StringUtils.substringAfter(line, "-")).replaceAll("\"", ""));
                    genderLabels.add(row);

                    if (counter == 0) {
                        api.put("age", row.get("label"));
                    }
                    counter++;
                }
            }
            map.put("gender", genderLabels);

        } catch (IOException e) {
            log.error("", e);
        } catch (InterruptedException e) {
            log.error("", e);
        } finally {
            try {
                if (reader != null) reader.close();
            } catch (IOException e) {
                log.error("", e);
            }

            if (process != null) {
                process.destroy();
            }
        }

        map.put("image", IMG_OSS_URL + image);
        api.put("image", map.get("image"));
        if (!map.isEmpty()) {
            //TODO save

            // 判断图片名称，符合 "渠道ID_时间戳.jpg" 格式的图片保存入库

            ObjectMapper objectMapper = new ObjectMapper();
            try {
                String json = objectMapper.writeValueAsString(map);
                log.debug("save {}, {}", image, json);
            } catch (JsonProcessingException e) {
                log.error("", e);
            }
        }

        log.debug("end...");
        return api;
    }
}
