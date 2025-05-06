package com.example.fsdemo.config;

import com.example.fsdemo.exceptions.FfmpegInitializationException;
import net.bramp.ffmpeg.FFmpeg;
import net.bramp.ffmpeg.FFmpegExecutor;
import net.bramp.ffmpeg.FFprobe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

@Configuration
public class FfmpegConfig {

    private static final Logger log = LoggerFactory.getLogger(FfmpegConfig.class);

    @Value("${ffmpeg.path}")
    private String ffmpegPath;

    @Value("${ffprobe.path}")
    private String ffprobePath;

    @Bean
    public FFmpeg fFmpeg() {
        if (ffmpegPath == null || ffmpegPath.isBlank()) {
            log.error("ffmpeg.path is not configured. FFmpeg cannot be initialized.");
            throw new FfmpegInitializationException("ffmpeg.path is required but not configured.", null);
        }
        try {
            log.info("Creating FFmpeg bean with path: {}", ffmpegPath);
            return new FFmpeg(ffmpegPath);
        } catch (IOException e) {
            throw new FfmpegInitializationException("Failed to initialize FFmpeg with path: " + ffmpegPath, e);
        } catch (IllegalArgumentException e) {
            throw new FfmpegInitializationException("Invalid configuration for FFmpeg path: " + ffmpegPath, e);
        }
    }

    @Bean
    public FFprobe fFprobe() {
        if (ffprobePath == null || ffprobePath.isBlank()) {
            log.warn("ffprobe.path is not configured. FFprobe bean will not be available.");
            return null;
        }
        try {
            log.info("Creating FFprobe bean with path: {}", ffprobePath);
            return new FFprobe(ffprobePath);
        } catch (IOException e) {
            throw new FfmpegInitializationException("Failed to initialize FFprobe with path: " + ffprobePath, e);
        } catch (IllegalArgumentException e) {
            throw new FfmpegInitializationException("Invalid configuration for FFprobe path: " + ffprobePath, e);
        }
    }

    @Bean
    public FFmpegExecutor fFmpegExecutor(FFmpeg ffmpeg, @Autowired(required = false) FFprobe ffprobe) throws IOException {
        log.info("Creating FFmpegExecutor bean");
        if (ffprobe != null) {
            log.debug("FFmpegExecutor created with FFmpeg and FFprobe");
            return new FFmpegExecutor(ffmpeg, ffprobe);
        } else {
            log.debug("FFmpegExecutor created with FFmpeg only");
            return new FFmpegExecutor(ffmpeg);
        }
    }
}