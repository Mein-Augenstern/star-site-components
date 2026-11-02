/*
 * Copyright 2026 muyi
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.xp.starsite.schedule.trigger;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 6 字段 cron 表达式（秒 分 时 日 月 周，Quartz 风格）——自研解析与推算，零第三方依赖
 * （护城河叙事同 sequence 时钟回拨钳制：可控、可测、可读）。
 *
 * <p>支持语法：{@code *}、{@code ?}（仅日/周，等同 *）、单值、{@code a-b} 区间、
 * {@code a-b/n} 与全域步进（起点/步进，如分字段 {@code 0/5}=0 起 5 步）逗号组合；
 * 月（JAN-DEC）与周（SUN-SAT）英文缩写不区分大小写；周 0-7（0 与 7 均为周日）。
 *
 * <p>日/周语义（Quartz 同款）：两字段都受限时为 <b>OR</b>（任一匹配即命中），
 * 其中一个为 {@code *}/{@code ?} 时由另一个决定——避开 Spring 与 Quartz 在此处的
 * 历史差异坑，行为在测试矩阵中钉死。
 *
 * <p>推算采用字段攀爬算法（月→日→时→分→秒逐级归位），上界 4 年（2/29 场景），
 * 超上界抛 {@link IllegalArgumentException}（表达式永不命中，装配期就该暴露）。
 *
 * @author muyi
 * @date 2026/11/02
 */
public class CronExpression {

    /** 字段攀爬上界（年）：覆盖 2 月 29 日最稀疏命中（4 年一闰）；超界视为永不命中 */
    private static final int MAX_YEARS_TO_SCAN = 4;

    /** 各字段位匹配集（下标 0-5：秒 分 时 日 月 周；周为 0-7 共 8 位，7 归一到 0） */
    private final boolean[][] matchSets;

    /** 日字段是否受限（非 * / ?）——OR 语义判定用 */
    private final boolean dayOfMonthRestricted;

    /** 周字段是否受限（非 * / ?）——OR 语义判定用 */
    private final boolean dayOfWeekRestricted;

    /** 原始表达式（describe/相等性用） */
    private final String expression;

    /** 各字段合法域（min/max；周字段 0-7） */
    private static final int[][] RANGES = {{0, 59}, {0, 59}, {0, 23}, {1, 31}, {1, 12}, {0, 7}};

    /** 月英文名（JAN=1） */
    private static final String[] MONTH_NAMES = {
        "JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"};

    /** 周英文名（SUN=0 起与数字一致） */
    private static final String[] WEEK_NAMES = {
        "SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"};

    /**
     * 解析并校验表达式。
     *
     * @param expression 6 字段 cron 表达式
     * @throws IllegalArgumentException 字段数≠6 / 值越界 / 语法非法
     */
    public CronExpression(String expression) {
        this.expression = expression == null ? "" : expression.trim();
        String[] fields = this.expression.split("\\s+");
        if (fields.length != 6) {
            throw new IllegalArgumentException(
                    "cron 需要 6 个字段（秒 分 时 日 月 周）: " + this.expression);
        }
        matchSets = new boolean[6][];
        for (int i = 0; i < 6; i++) {
            matchSets[i] = parseField(fields[i], i);
        }
        dayOfMonthRestricted = isRestricted(fields[3], 3);
        dayOfWeekRestricted = isRestricted(fields[5], 5);
    }

    /**
     * 推算严格晚于 after 的下一个命中时刻。
     *
     * @param after 基准时刻（含时区语义）
     * @return 下一个命中时刻（同区）
     * @throws IllegalArgumentException 4 年内无命中（如 2 月 30 日）
     */
    public ZonedDateTime next(ZonedDateTime after) {
        ZonedDateTime t = after.plusSeconds(1).withNano(0);
        ZonedDateTime ceiling = after.plusYears(MAX_YEARS_TO_SCAN);
        while (t.isBefore(ceiling)) {
            // 月攀爬：未命中则跳到下一个命中月 1 日 00:00:00
            if (!matchSets[4][t.getMonthValue()]) {
                t = nextMonthStart(t);
                continue;
            }
            // 日攀爬：日/周 OR 语义判定，未命中则次日 00:00:00
            if (!dayMatches(t)) {
                t = t.plusDays(1).withHour(0).withMinute(0).withSecond(0);
                continue;
            }
            // 时攀爬
            if (!matchSets[2][t.getHour()]) {
                int next = nextMatch(matchSets[2], t.getHour(), 23);
                if (next < 0) {
                    t = t.plusDays(1).withHour(0).withMinute(0).withSecond(0);
                    continue;
                }
                t = t.withHour(next).withMinute(0).withSecond(0);
                continue;
            }
            // 分攀爬
            if (!matchSets[1][t.getMinute()]) {
                int next = nextMatch(matchSets[1], t.getMinute(), 59);
                if (next < 0) {
                    t = t.plusHours(1).withMinute(0).withSecond(0);
                    continue;
                }
                t = t.withMinute(next).withSecond(0);
                continue;
            }
            // 秒攀爬
            if (!matchSets[0][t.getSecond()]) {
                int next = nextMatch(matchSets[0], t.getSecond(), 59);
                if (next < 0) {
                    t = t.plusMinutes(1).withSecond(0);
                    continue;
                }
                t = t.withSecond(next);
                continue;
            }
            return t;
        }
        throw new IllegalArgumentException(
                "cron 表达式 " + MAX_YEARS_TO_SCAN + " 年内无命中时刻: " + expression);
    }

    /**
     * 便捷重载：无时区上下文的推算（LocalDateTime 按 systemDefault 解释）。
     *
     * @param after 基准时刻
     * @param zone  时区
     * @return 下一个命中时刻（LocalDateTime 形态）
     */
    public LocalDateTime nextAfter(LocalDateTime after, ZoneId zone) {
        return next(after.atZone(zone)).toLocalDateTime();
    }

    /**
     * 获取原始表达式。
     *
     * @return trim 后的表达式
     */
    public String getExpression() {
        return expression;
    }

    /** 日/周 OR 语义：两者都受限时任一命中即命中；一个受限由它决定；都无限即命中 */
    private boolean dayMatches(ZonedDateTime t) {
        boolean domHit = matchSets[3][t.getDayOfMonth()];
        boolean dowHit = matchSets[5][t.getDayOfWeek().getValue() % 7];
        if (dayOfMonthRestricted && dayOfWeekRestricted) {
            return domHit || dowHit;
        }
        if (dayOfMonthRestricted) {
            return domHit;
        }
        if (dayOfWeekRestricted) {
            return dowHit;
        }
        return true;
    }

    /** 跳到下一个命中月的 1 日零点（年内逐月推进，跨年回 1 月起扫；全新构造避开 withMonth 在大月末日的越界） */
    private ZonedDateTime nextMonthStart(ZonedDateTime t) {
        int year = t.getYear();
        int next = nextMatch(matchSets[4], t.getMonthValue(), 12);
        if (next < 0) {
            year++;
            next = firstMatch(matchSets[4], 1);
        }
        return ZonedDateTime.of(year, next, 1, 0, 0, 0, 0, t.getZone());
    }

    /** 从 from 起找下一个命中下标；无则返回 -1 */
    private static int nextMatch(boolean[] set, int from, int max) {
        for (int v = from + 1; v <= max; v++) {
            if (set[v]) {
                return v;
            }
        }
        return -1;
    }

    /** 从 from 起找第一个命中下标（含 from；无则 from——由上层兜底再攀爬） */
    private static int firstMatch(boolean[] set, int from) {
        for (int v = from; v < set.length; v++) {
            if (set[v]) {
                return v;
            }
        }
        return from;
    }

    /** 字段是否受限（* 与 ? 视为无限） */
    private static boolean isRestricted(String field, int index) {
        return !"*".equals(field) && !"?".equals(field);
    }

    /** 解析单字段为匹配集（越界步进等非法在此暴露） */
    private static boolean[] parseField(String field, int index) {
        int min = RANGES[index][0];
        int max = RANGES[index][1];
        boolean[] set = new boolean[max + 1];
        for (String part : field.split(",", -1)) {
            parsePart(part, index, min, max, set);
        }
        return set;
    }

    /** 解析逗号分段：单值 / 区间 / 步进（a/n=从 a 到字段上限按 n 步；a-b/n=区间内步进） */
    private static void parsePart(String part, int index, int min, int max, boolean[] set) {
        if (part.isEmpty()) {
            throw new IllegalArgumentException("cron 字段存在空段: " + part);
        }
        String range = part;
        int slash = part.indexOf('/');
        int step = 1;
        boolean hasStep = slash >= 0;
        if (hasStep) {
            range = part.substring(0, slash);
            try {
                step = Integer.parseInt(part.substring(slash + 1));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("cron 步进非法: " + part);
            }
            if (step <= 0) {
                throw new IllegalArgumentException("cron 步进必须为正: " + part);
            }
        }
        int from;
        int to;
        if ("*".equals(range) || "?".equals(range)) {
            from = min;
            to = max;
        } else if (range.contains("-")) {
            String[] bounds = range.split("-", -1);
            if (bounds.length != 2) {
                throw new IllegalArgumentException("cron 区间非法: " + part);
            }
            from = parseValue(bounds[0], index, min, max);
            to = parseValue(bounds[1], index, min, max);
        } else {
            from = parseValue(range, index, min, max);
            // a/n 语义：从 a 到字段上限按 n 步（Quartz 惯例，如分字段 0/5=0,5,…,55）；
            // 无步进的单值就是单点命中
            to = hasStep ? max : from;
        }
        if (from > to) {
            throw new IllegalArgumentException("cron 区间起点大于终点: " + part);
        }
        for (int v = from; v <= to; v += step) {
            set[v] = true;
        }
    }

    /** 解析单值：数字或月/周英文名（周 7 归一为 0=周日） */
    private static int parseValue(String token, int index, int min, int max) {
        String upper = token.toUpperCase(Locale.ROOT);
        int value;
        if (index == 4) {
            value = nameIndex(MONTH_NAMES, upper) + 1;
            if (value == 0) {
                value = parseInt(token);
            }
        } else if (index == 5) {
            int ni = nameIndex(WEEK_NAMES, upper);
            value = ni < 0 ? parseInt(token) : ni;
            if (value == 7) {
                value = 0;
            }
        } else {
            value = parseInt(token);
        }
        if (value < min || value > max) {
            throw new IllegalArgumentException(
                    "cron 字段值越界（" + min + "-" + max + "）: " + token);
        }
        return value;
    }

    /** 英文名查下标；未命中返回 -1 */
    private static int nameIndex(String[] names, String token) {
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(token)) {
                return i;
            }
        }
        return -1;
    }

    /** 数字解析（非法即抛） */
    private static int parseInt(String token) {
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("cron 字段值非法: " + token);
        }
    }

    /**
     * 调试用命中集描述。
     *
     * @return 各字段命中值列表
     */
    @Override
    public String toString() {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            List<Integer> hits = new ArrayList<>();
            for (int v = 0; v < matchSets[i].length; v++) {
                if (matchSets[i][v]) {
                    hits.add(v);
                }
            }
            parts.add(Arrays.toString(hits.toArray()));
        }
        return "CronExpression{" + expression + " " + parts + "}";
    }
}
