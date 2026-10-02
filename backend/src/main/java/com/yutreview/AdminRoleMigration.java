package com.yutreview;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.orm.jpa.EntityManagerFactoryDependsOnPostProcessor;
import org.springframework.stereotype.Component;

/**
 * 기동 시 한 번 도는 데이터 이관. 마이그레이션 도구가 없고 `ddl-auto=update`는 값·제약을 고치지 못해서 둔다.
 *
 * <b>JPA보다 먼저</b> 돈다(아래 {@link Order}). 옛 값 `SYSTEM_ADMIN`이 남은 채로 Hibernate가 계정을 읽으면
 * enum 변환에서 터지므로, 어떤 요청도 받기 전에 값을 바꿔 둬야 한다.
 *
 * 1. 운영자 역할 값 `SYSTEM_ADMIN` → `OPERATOR` (2026-10-01, "admin"은 매장 관리자만 뜻하도록 분리).
 *    Hibernate가 enum 값 목록으로 만든 CHECK 제약이 옛 값만 허용하므로 제약을 지우고 새 목록으로 다시 건다.
 * 2. 기본 상품 설명 "관리자에서 상품을 설정하세요."를 비운다. 손님 화면 상품 목록에 그대로 보이던 문구다.
 *    현재 상품 설정만 고친다. 이미 발급된 쿠폰의 스냅샷은 건드리지 않는다.
 * 3. `operator_store_actions.action`의 CHECK 제약을 지금 {@link StoreCareAction} 목록으로 다시 건다.
 *    `ddl-auto=update`는 제약을 고치지 못해서, enum에 값을 더하면(2026-10-03 STAFF_PIN_RESET) 새 값 INSERT가 실패한다.
 *
 * 전부 멱등이다. 매 기동마다 돌아도 두 번째부터는 바꿀 행이 없다. 한 트랜잭션이라 중간에 실패하면
 * 아무것도 바뀌지 않고 기동이 멈춘다(반쯤 바뀐 역할 값으로 뜨는 것보다 낫다).
 * PostgreSQL에서만 돈다. 테스트 H2는 매번 새 스키마(create-drop)라 옮길 값이 없다.
 */
@Component("adminRoleMigration")
class AdminRoleMigration {
    private static final Logger log=LoggerFactory.getLogger(AdminRoleMigration.class);
    static final String LEGACY_PRIZE_DESCRIPTION="관리자에서 상품을 설정하세요.";

    AdminRoleMigration(DataSource dataSource) throws SQLException {
        try(Connection c=dataSource.getConnection()){
            if(!"PostgreSQL".equals(c.getMetaData().getDatabaseProductName()))return;
            migrate(c);
        }
    }

    /** 결과 요약(바뀐 행 수)을 돌려준다. 테스트와 로그용. */
    static int[] migrate(Connection c) throws SQLException {
        boolean auto=c.getAutoCommit();c.setAutoCommit(false);
        try(Statement st=c.createStatement()){
            int roles=0,prizes=0;
            if(exists(st,"admin_users")){
                List<String> checks=new ArrayList<>();
                try(ResultSet rs=st.executeQuery("select conname from pg_constraint where conrelid='admin_users'::regclass "
                        +"and contype='c' and pg_get_constraintdef(oid) ilike '%role%'")){while(rs.next())checks.add(rs.getString(1));}
                for(String name:checks)st.execute("alter table admin_users drop constraint \""+name.replace("\"","\"\"")+"\"");
                roles=st.executeUpdate("update admin_users set role='OPERATOR' where role='SYSTEM_ADMIN'");
                st.execute("alter table admin_users add constraint admin_users_role_check check (role in ('OPERATOR','STORE_ADMIN'))");
            }
            if(exists(st,"operator_store_actions")){
                List<String> checks=new ArrayList<>();
                try(ResultSet rs=st.executeQuery("select conname from pg_constraint where conrelid='operator_store_actions'::regclass "
                        +"and contype='c' and pg_get_constraintdef(oid) ilike '%action%'")){while(rs.next())checks.add(rs.getString(1));}
                for(String name:checks)st.execute("alter table operator_store_actions drop constraint \""+name.replace("\"","\"\"")+"\"");
                String values=java.util.Arrays.stream(StoreCareAction.values()).map(a->"'"+a.name()+"'").collect(java.util.stream.Collectors.joining(","));
                st.execute("alter table operator_store_actions add constraint operator_store_actions_action_check check (action in ("+values+"))");
            }
            if(exists(st,"prizes"))prizes=st.executeUpdate("update prizes set description=null where description='"+LEGACY_PRIZE_DESCRIPTION+"'");
            c.commit();
            if(roles>0||prizes>0)log.info("data migration applied: operatorRoles={}, legacyPrizeDescriptions={}",roles,prizes);
            return new int[]{roles,prizes};
        }catch(SQLException|RuntimeException e){c.rollback();throw e;}
        finally{c.setAutoCommit(auto);}
    }

    private static boolean exists(Statement st,String table) throws SQLException {
        try(ResultSet rs=st.executeQuery("select to_regclass('"+table+"') is not null")){rs.next();return rs.getBoolean(1);}
    }

    /** EntityManagerFactory가 이 빈 뒤에 만들어지게 한다. 이게 없으면 Hibernate가 먼저 떠서 옛 값을 읽는다. */
    @Component static class Order extends EntityManagerFactoryDependsOnPostProcessor {
        Order(){super("adminRoleMigration");}
    }
}
