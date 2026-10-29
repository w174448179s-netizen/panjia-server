package org.dromara.common.mybatis.config;

import cn.hutool.core.net.NetUtil;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.core.handlers.PostInitTableInfoHandler;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.dromara.common.core.factory.YmlPropertySourceFactory;
import org.dromara.common.mybatis.aspect.DataPermissionPointcutAdvisor;
import org.dromara.common.mybatis.config.properties.SqlLogProperties;
import org.dromara.common.mybatis.handler.InjectionMetaObjectHandler;
import org.dromara.common.mybatis.handler.MybatisExceptionHandler;
import org.dromara.common.mybatis.handler.PlusPostInitTableInfoHandler;
import org.dromara.common.mybatis.interceptor.PlusDataPermissionInterceptor;
import org.dromara.common.mybatis.interceptor.SqlLogInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.PropertySource;
import org.springframework.context.annotation.Role;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;

/**
 * mybatis-plus配置类(下方注释有插件介绍)
 *
 * @author Lion Li
 */
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
@EnableTransactionManagement(proxyTargetClass = true)
@MapperScan("${mybatis-plus.mapperPackage}")
@PropertySource(value = "classpath:common-mybatis.yml", factory = YmlPropertySourceFactory.class)
@EnableConfigurationProperties(SqlLogProperties.class)
public class MybatisPlusConfig {

    /**
     * 组装 MyBatis-Plus 核心拦截器链。
     *
     * @return MyBatis-Plus 拦截器
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 数据权限处理
        interceptor.addInnerInterceptor(dataPermissionInterceptor());
        // 分页插件
        interceptor.addInnerInterceptor(paginationInnerInterceptor());
        // 乐观锁插件
        interceptor.addInnerInterceptor(optimisticLockerInnerInterceptor());
        return interceptor;
    }

    /**
     * 数据权限拦截器
     */
    public PlusDataPermissionInterceptor dataPermissionInterceptor() {
        return new PlusDataPermissionInterceptor();
    }

    /**
     * 数据权限切面处理器
     */
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public DataPermissionPointcutAdvisor dataPermissionPointcutAdvisor() {
        return new DataPermissionPointcutAdvisor();
    }

    /**
     * 分页插件，自动识别数据库类型
     */
    public PaginationInnerInterceptor paginationInnerInterceptor() {
        PaginationInnerInterceptor paginationInnerInterceptor = new PaginationInnerInterceptor();
        // 分页合理化
        paginationInnerInterceptor.setOverflow(true);
        return paginationInnerInterceptor;
    }

    /**
     * 乐观锁插件
     */
    public OptimisticLockerInnerInterceptor optimisticLockerInnerInterceptor() {
        return new OptimisticLockerInnerInterceptor();
    }

    /**
     * 完整 SQL 日志拦截器
     */
    @Bean
    @ConditionalOnProperty(prefix = "mybatis-plus.sql-log", name = "enabled", havingValue = "true")
    public SqlLogInterceptor sqlLogInterceptor(SqlLogProperties sqlLogProperties) {
        return new SqlLogInterceptor(sqlLogProperties);
    }

    /**
     * 元对象字段填充控制器
     */
    @Bean
    public MetaObjectHandler metaObjectHandler() {
        return new InjectionMetaObjectHandler();
    }

    /**
     * 使用网卡信息绑定雪花生成器
     * 防止集群雪花ID重复
     */
    @Bean
    public IdentifierGenerator idGenerator() {
        return new DefaultIdentifierGenerator(NetUtil.getLocalhost());
    }

    /**
     * 异常处理器
     */
    @Bean
    public MybatisExceptionHandler mybatisExceptionHandler() {
        return new MybatisExceptionHandler();
    }

    /**
     * 初始化表对象处理器
     */
    @Bean
    public PostInitTableInfoHandler postInitTableInfoHandler() {
        return new PlusPostInitTableInfoHandler();
    }

    /**
     * 显式注册 DataSourceTransactionManager。
     * <p>
     * 根因背景：dynamic-datasource 4.5.0 自动注册的 DynamicRoutingDataSource，在 Spring Boot 4
     * 的 DataSourceAutoConfiguration 场景下，可能因为自动配置条件不满足（dynamic-datasource starter
     * 未显式 exclude DataSourceAutoConfiguration 但自动配置优先级不明确）导致 Spring 默认的
     * DataSourceTransactionManager 没注册上，@Transactional 看起来生效（代理正常）但 Connection
     * 绑定没走 Spring 事务管理——每个 Mapper INSERT 拿独立 Connection → PostgreSQL 每个 INSERT 开
     * 独立事务。实锤证据（panjia-test 162.14.72.197）：
     * <pre>
     * txid=21494: 1802 条 flow_instance (Warm-Flow FlowInstanceMapper)
     * txid=21502: 1443 条 pj_commission_application (ruoyi CommissionApplicationMapper)
     * 两个 txid 完全独立，flow_xmin 全 21494, app_xmin 全 21502，
     * 正常单和脏单都在这两个事务里。
     * </pre>
     * 显式注册 + @Primary 确保所有模块（ruoyi-commission、Warm-Flow ruoyi-workflow、dynamic-datasource）
     * 的 Mapper 调用都走同一个 Spring PlatformTransactionManager → 同一个 Connection → 同一个 PG 事务。
     */
    @Bean
    @Primary
    public DataSourceTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    /**
     * PaginationInnerInterceptor 分页插件，自动识别数据库类型
     * https://baomidou.com/pages/97710a/
     * OptimisticLockerInnerInterceptor 乐观锁插件
     * https://baomidou.com/pages/0d93c0/
     * MetaObjectHandler 元对象字段填充控制器
     * https://baomidou.com/pages/4c6bcf/
     * ISqlInjector sql注入器
     * https://baomidou.com/pages/42ea4a/
     * BlockAttackInnerInterceptor 如果是对全表的删除或更新操作，就会终止该操作
     * https://baomidou.com/pages/f9a237/
     * IllegalSQLInnerInterceptor sql性能规范插件(垃圾SQL拦截)
     * IdentifierGenerator 自定义主键策略
     * https://baomidou.com/pages/568eb2/
     * TenantLineInnerInterceptor 多租户插件
     * https://baomidou.com/pages/aef2f2/
     * DynamicTableNameInnerInterceptor 动态表名插件
     * https://baomidou.com/pages/2a45ff/
     */

}
