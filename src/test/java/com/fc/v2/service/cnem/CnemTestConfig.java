package com.fc.v2.service.cnem;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fc.v2.service.impl.TCnemJobServiceImpl;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.Date;

/**
 * 作业生命周期测试用的最小 Spring 装配：H2(MySQL 模式) + 真实 MyBatis-Plus mapper + 真实事务。
 * 不引 Shiro/Redis/Druid，自动填充的操作人固定为 tester。
 */
@Configuration
@MapperScan("com.fc.v2.mapper.auto")
public class CnemTestConfig {

    @Bean
    public DataSource dataSource() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:cnem;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        ds.setUsername("sa");
        ds.setPassword("");
        resetSchema(ds);
        return ds;
    }

    /** 每个用例前把内存库清掉重建（由测试基类调用） */
    public static void resetSchema(DataSource ds) {
        try (java.sql.Connection c = ds.getConnection()) {
            try (java.sql.Statement st = c.createStatement()) {
                st.execute("DROP ALL OBJECTS");
            }
            ScriptUtils.executeSqlScript(c, new ClassPathResource("cnem-h2.sql"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Bean
    public SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        MybatisConfiguration configuration = new MybatisConfiguration();
        factory.setConfiguration(configuration);
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setIdentifierGenerator(new DefaultIdentifierGenerator());
        globalConfig.setMetaObjectHandler(new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject metaObject) {
                this.fillStrategy(metaObject, "createBy", "tester");
                this.strictInsertFill(metaObject, "createTime", Date.class, new Date());
            }

            @Override
            public void updateFill(MetaObject metaObject) {
                this.fillStrategy(metaObject, "updateBy", "tester");
                this.strictUpdateFill(metaObject, "updateTime", Date.class, new Date());
            }
        });
        factory.setGlobalConfig(globalConfig);
        return factory.getObject();
    }

    @Bean
    public PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    public TCnemJobServiceImpl cnemJobService() {
        return new TCnemJobServiceImpl();
    }
}
