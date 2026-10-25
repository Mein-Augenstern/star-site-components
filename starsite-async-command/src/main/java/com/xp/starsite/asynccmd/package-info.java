/**
 * 异步命令核心库：提交客户端 + 令牌认领调度器 + 双存储（MySQL 表 / 进程内存）。
 * 仅依赖 spring-jdbc（表存储用），可独立于 starter 使用；装配见 starter 模块。
 *
 * @author muyi
 * @date 2026/10/25
 */
package com.xp.starsite.asynccmd;
