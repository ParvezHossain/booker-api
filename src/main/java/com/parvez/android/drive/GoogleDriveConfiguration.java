package com.parvez.android.drive;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Registers settings and scheduled import/file cleanup workers without mixing bootstrap with validation. */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(GoogleDriveSettings.class)
public class GoogleDriveConfiguration {}
