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
package com.xp.starsite.idempotent;

import com.xp.starsite.idempotent.api.IdempotentAction;
import com.xp.starsite.idempotent.api.IdempotentClient;
import com.xp.starsite.idempotent.api.IdempotentConflictException;
import com.xp.starsite.idempotent.api.IdempotentFailedException;
import com.xp.starsite.idempotent.api.IdempotentResult;
import com.xp.starsite.idempotent.api.StarsiteIdempotent;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.env.Environment;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.util.ClassUtils;

/**
 * {@link StarsiteIdempotent @StarsiteIdempotent} 注解代理器：Bean 初始化后扫方法注解，
 * 有注解的 Bean 包装为代理——方法调用经 {@link IdempotentClient} 走幂等协议
 * （认领/回放/冲突语义与门面完全一致）。
 *
 * <p>键解析两种形态：含 {@code #} 按 SpEL 对方法参数求值（参数名经字节码调试信息解析）；
 * 否则字面量（先解析 {@code ${...}} 占位符——键进 yml 集中管理）。scope 缺省
 * 类简单名#方法名，支持占位符。
 *
 * <p><b>启动期快速失败（不静默降级——静默不幂等比报错危险）</b>：
 * <ul>
 *   <li>classpath 缺 Jackson（结果回放的序列化载体）却存在注解 → 启动失败并给引导</li>
 *   <li>键求值为 null/空串 → 调用期抛（幂等键缺失即语义不成立）</li>
 * </ul>
 *
 * @author muyi
 * @date 2026/11/12
 */
public class IdempotentBeanPostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(IdempotentBeanPostProcessor.class);

    /** 幂等门面（惰性提供器——BPP 创建早于引擎 Bean，首次代理创建时解析） */
    private final ObjectProvider<IdempotentClient> clientProvider;

    /** 环境抽象：注解属性 ${...} 占位符解析（schedule BPP 同款 DX） */
    private final Environment environment;

    /** SpEL 解析器（无状态，线程安全） */
    private final SpelExpressionParser parser = new SpelExpressionParser();

    /** 参数名发现器（字节码调试信息——编译期未开 -parameters 也可解析） */
    private final ParameterNameDiscoverer paramNames = new DefaultParameterNameDiscoverer();

    /** 键表达式缓存（方法 → 解析后的表达式/字面量，避免每调用重复解析） */
    private final Map<Method, ResolvedKey> keyCache = new ConcurrentHashMap<>();

    /**
     * 构造。
     *
     * @param clientProvider 幂等门面惰性提供器
     * @param environment    环境抽象（占位符解析）
     */
    public IdempotentBeanPostProcessor(ObjectProvider<IdempotentClient> clientProvider,
                                       Environment environment) {
        this.clientProvider = clientProvider;
        this.environment = environment;
    }

    /** {@inheritDoc} 扫到注解方法即包装代理（无注解 Bean 原样返回，零开销） */
    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        Class<?> target = bean.getClass();
        boolean annotated = false;
        for (Method m : target.getDeclaredMethods()) {
            if (m.isAnnotationPresent(StarsiteIdempotent.class)) {
                annotated = true;
                break;
            }
        }
        if (!annotated) {
            return bean;
        }
        if (!ClassUtils.isPresent("com.fasterxml.jackson.databind.ObjectMapper",
                target.getClassLoader())) {
            // 回放载体缺失：静默跳过代理 = 请求全部裸执行，幂等承诺成空话——宁可启动失败
            throw new IllegalStateException("发现 @StarsiteIdempotent 注解但 classpath 缺 "
                    + "jackson-databind（结果回放的序列化载体）——引依赖或移除注解（门面路径"
                    + "不受影响：业务自管载荷字符串）: " + target.getName());
        }
        IdempotentClient client = clientProvider.getIfAvailable();
        if (client == null) {
            throw new IllegalStateException("发现 @StarsiteIdempotent 注解但容器无 "
                    + "IdempotentClient Bean（检查 starsite.idempotent.enabled）: " + target.getName());
        }
        log.info("[starsite-idempotent] {} 启用幂等代理（{} 方法级注解）", target.getSimpleName(),
                java.util.Arrays.stream(target.getDeclaredMethods())
                        .filter(m -> m.isAnnotationPresent(StarsiteIdempotent.class)).count());
        ProxyFactory factory = new ProxyFactory();
        factory.setTarget(bean);
        factory.setInterfaces(target.getInterfaces());
        factory.addAdvice(new IdempotentMethodInterceptor(client, this));
        return factory.getProxy(target.getClassLoader());
    }

    /**
     * 解析键（缓存）：占位符先行，再按含 {@code #} 与否决定 SpEL/字面量。
     *
     * @param method 注解方法（键表达式载体）
     * @param anno   注解
     * @return 解析结果（表达式或字面量）
     */
    ResolvedKey resolveKey(Method method, StarsiteIdempotent anno) {
        return keyCache.computeIfAbsent(method, m -> {
            String raw = environment.resolvePlaceholders(anno.key());
            if (raw.contains("#")) {
                return ResolvedKey.spel(parser.parseExpression(raw));
            }
            return ResolvedKey.literal(raw);
        });
    }

    /**
     * 解析 scope（占位符 → 缺省类简单名#方法名）。
     *
     * @param method 注解方法
     * @param anno   注解
     * @return 作用域
     */
    static String resolveScope(Method method, StarsiteIdempotent anno) {
        String raw = anno.scope();
        return raw.isBlank() ? method.getDeclaringClass().getSimpleName() + "#" + method.getName()
                : raw.trim();
    }

    /** 键解析结果（SpEL 表达式或字面量——求值策略随形态） */
    record ResolvedKey(Expression expression, String literal) {

        /**
         * 构造 SpEL 形态。
         *
         * @param expression 已解析表达式
         * @return 解析结果
         */
        static ResolvedKey spel(Expression expression) {
            return new ResolvedKey(expression, null);
        }

        /**
         * 构造字面量形态。
         *
         * @param literal 字面量
         * @return 解析结果
         */
        static ResolvedKey literal(String literal) {
            return new ResolvedKey(null, literal);
        }
    }

    /**
     * 幂等方法拦截器：键求值 → 门面协议 → 结果回放（Jackson 往返；
     * 类级内部类——仅 Jackson 在场时实例化，宿主无 Jackson 不加载本类）。
     */
    static class IdempotentMethodInterceptor implements MethodInterceptor {

        /** 幂等门面 */
        private final IdempotentClient client;

        /** 所属 BPP（键缓存与解析器复用） */
        private final IdempotentBeanPostProcessor owner;

        /** 回放序列化载体（宿主 ObjectMapper 惰性解析缓存） */
        private volatile com.fasterxml.jackson.databind.ObjectMapper mapper;

        /**
         * 构造拦截器。
         *
         * @param client 幂等门面
         * @param owner  所属 BPP
         */
        IdempotentMethodInterceptor(IdempotentClient client, IdempotentBeanPostProcessor owner) {
            this.client = client;
            this.owner = owner;
        }

        /** {@inheritDoc} 注解方法走幂等协议，非注解方法直通 */
        @Override
        public Object invoke(MethodInvocation inv) throws Throwable {
            Method method = AopUtils.getMostSpecificMethod(inv.getMethod(),
                    inv.getThis().getClass());
            StarsiteIdempotent anno = method.getAnnotation(StarsiteIdempotent.class);
            if (anno == null) {
                return inv.proceed();
            }
            String key = evaluateKey(method, anno, inv.getArguments());
            if (key == null || key.isBlank()) {
                throw new IllegalStateException("@StarsiteIdempotent 键求值为空（表达式: "
                        + anno.key() + "）——检查参数值是否为 null；参数名引用（#orderId）需"
                        + " -parameters 编译（Boot 3.2+ 默认开），或改位置引用 #p0");
            }
            String scope = resolveScope(method, anno);
            Object[] original = new Object[1];
            IdempotentResult result = client.guard(scope, key, (IdempotentAction) () -> {
                try {
                    original[0] = inv.proceed();
                } catch (RuntimeException | Error e) {
                    throw e; // 原生 unchecked 原样上抛——引擎按原类型重抛/标记 FAILED
                } catch (Throwable t) {
                    throw new IllegalStateException("幂等方法执行失败（受检异常包装）", t);
                }
                return serialize(original[0], method);
            });
            return switch (result.getKind()) {
                case EXECUTED -> original[0]; // 本请求执行过——返回真实对象（零序列化损耗）
                case REPLAYED -> deserialize(result.getPayload(), method);
                case CONFLICT -> throw new IdempotentConflictException(
                        "幂等键在飞（他方执行中，等待窗口耗尽）: " + scope + "#" + key);
                case FAILED -> throw new IdempotentFailedException(String.valueOf(result.getError()));
            };
        }

        /**
         * 求值键（SpEL 对参数 / 字面量）。参数绑定三级：参数名（需 -parameters 编译，
         * Boot 3.2+ 项目默认开）/ #p0/#a0 位置引用（参数名不可用时的兜底 DX）/ 字面量。
         *
         * @param method 注解方法
         * @param anno   注解
         * @param args   调用实参
         * @return 键；求值失败为 null
         */
        private String evaluateKey(Method method, StarsiteIdempotent anno, Object[] args) {
            ResolvedKey resolved = owner.resolveKey(method, anno);
            if (resolved.literal() != null) {
                return resolved.literal();
            }
            StandardEvaluationContext ctx = new StandardEvaluationContext();
            String[] names = owner.paramNames.getParameterNames(method);
            if (names != null) {
                for (int i = 0; i < names.length && i < args.length; i++) {
                    ctx.setVariable(names[i], args[i]);
                }
            }
            // 位置引用兜底：Spring 6.1 起无 -parameters 参数名不可反射（LocalVariableTable
            // 发现器已移除）——#p0/#a0 让未开参数名的宿主也有确定键形态
            if (args != null) {
                for (int i = 0; i < args.length; i++) {
                    ctx.setVariable("p" + i, args[i]);
                    ctx.setVariable("a" + i, args[i]);
                }
            }
            Object value = resolved.expression().getValue(ctx);
            return value == null ? null : String.valueOf(value);
        }

        /**
         * 序列化回放载荷（void/null 返回 null——回放即空返回）。
         *
         * @param value  方法返回值
         * @param method 方法（返回类型判定）
         * @return JSON 载荷；void/null 为 null
         * @throws com.fasterxml.jackson.core.JsonProcessingException 序列化失败（不可 JSON 化的返回值）
         */
        private String serialize(Object value, Method method) throws Exception {
            if (method.getReturnType() == void.class || value == null) {
                return null;
            }
            return mapper().writeValueAsString(value);
        }

        /**
         * 反序列化回放载荷。
         *
         * @param payload 存储载荷
         * @param method  方法（目标返回类型）
         * @return 回放值；载荷为 null（void 首执行）为 null
         * @throws com.fasterxml.jackson.core.JsonProcessingException 反序列化失败
         */
        private Object deserialize(String payload, Method method) throws Exception {
            if (payload == null) {
                return null;
            }
            return mapper().readValue(payload, method.getReturnType());
        }

        /** ObjectMapper 惰性解析（宿主定制过的实例优先——与 MVC 序列化口径一致） */
        private com.fasterxml.jackson.databind.ObjectMapper mapper() {
            com.fasterxml.jackson.databind.ObjectMapper m = mapper;
            if (m == null) {
                m = new com.fasterxml.jackson.databind.ObjectMapper();
                mapper = m;
            }
            return m;
        }
    }
}
