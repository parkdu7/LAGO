package com.example.LAGO.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import java.io.FileInputStream;
import java.io.IOException;

@Configuration
@Slf4j
public class FirebaseConfig {

    @Value("${firebase.key-path:firebaseServiceAccountKey.json}")
    private String firebaseKeyPath;

    @PostConstruct
    public void initialize() {
        try {
            if (FirebaseApp.getApps().isEmpty()) {
                FileInputStream serviceAccount = new FileInputStream(firebaseKeyPath);
                
                FirebaseOptions options = FirebaseOptions.builder()
                        .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                        .build();
                
                FirebaseApp.initializeApp(options);
                log.info("Firebase application initialized successfully");
            } else {
                log.info("Firebase application already initialized");
            }
        } catch (IOException e) {
            log.error("Failed to initialize Firebase application", e);
        }
    }
}