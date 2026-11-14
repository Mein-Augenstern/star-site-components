package com.starsite.boot27;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Boot 2.7 端到端验证启动类。
 *
 * @author boot27-verify
 */
@SpringBootApplication
public class Boot27App {
    /**
     * 启动入口.
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(Boot27App.class, args);
    }
}
