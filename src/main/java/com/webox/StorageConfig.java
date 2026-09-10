package com.webox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import java.nio.file.Path;

@Configuration
class StorageConfig implements WebMvcConfigurer {
 private final Path uploadDir;
 StorageConfig(@Value("${webox.upload-dir:./uploads}") String dir){uploadDir=Path.of(dir).toAbsolutePath().normalize();}
 Path uploadDir(){return uploadDir;}
 @Override public void addResourceHandlers(ResourceHandlerRegistry registry){registry.addResourceHandler("/uploads/**").addResourceLocations(uploadDir.toUri().toString());}
}
