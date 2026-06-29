package com.docgen.config;

import com.syncfusion.licensing.SyncfusionLicenseProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SyncfusionLicenseConfig implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SyncfusionLicenseConfig.class);

    @Value("${syncfusion.license-key:}")
    private String licenseKey;

    @Override
    public void run(ApplicationArguments args) {
        if (licenseKey == null || licenseKey.isBlank()) {
            log.warn(
                    "SYNCFUSION_LICENSE_KEY is not set — DocIO runs in trial mode (watermark in output). "
                            + "Add the key to ../.env (repo root) or export the env var. "
                            + "See .env.example.");
            return;
        }
        SyncfusionLicenseProvider.registerLicense(licenseKey.trim());
        log.info("Syncfusion DocIO license registered (same key as frontend if Document SDK is included).");
    }
}
