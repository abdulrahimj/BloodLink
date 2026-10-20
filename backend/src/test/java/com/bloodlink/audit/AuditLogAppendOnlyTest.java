package com.bloodlink.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import org.hibernate.annotations.Immutable;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;

import com.bloodlink.domain.AuditLog;
import com.bloodlink.repository.AuditLogRepository;

/**
 * The code-level half of Business Rule 17's append-only log: nothing in Java can change or remove an entry.
 * Deliberately structural, so switching to JpaRepository fails the build with a reason.
 */
class AuditLogAppendOnlyTest {

    @Test
    void theRepositoryInheritsNoDeleteMethods() {
        // CrudRepository (and JpaRepository, which extends it) would bring delete,
        // deleteById, deleteAll and deleteAllInBatch with them.
        assertThat(CrudRepository.class.isAssignableFrom(AuditLogRepository.class))
            .as("AuditLogRepository must not extend CrudRepository/JpaRepository — they carry delete methods")
            .isFalse();
    }

    @Test
    void theRepositoryDeclaresNoWayToChangeOrRemoveAnEntry() {
        List<String> names = Arrays.stream(AuditLogRepository.class.getMethods()).map(Method::getName).toList();

        assertThat(names).noneMatch(name -> name.startsWith("delete") || name.startsWith("remove")
            || name.startsWith("update") || name.startsWith("modify"));
        // What it does have: a way to add, and ways to read.
        assertThat(names).contains("save", "findAllNewestFirst");
    }

    /**
     * The newest-first page query must keep its createdAt desc, id desc tie-breaker,
     * or rows sharing a timestamp could be skipped or repeated across pages.
     */
    @Test
    void theNewestFirstPageQueryKeepsTheIdTieBreaker() throws NoSuchMethodException {
        Method method = AuditLogRepository.class.getMethod("findAllNewestFirst", org.springframework.data.domain.Pageable.class);
        String jpql = method.getAnnotation(Query.class).value();

        assertThat(jpql).as("findAllNewestFirst's JPQL").contains("order by a.createdAt desc, a.id desc");
    }

    @Test
    void anEntryCannotBeAlteredOnceBuilt() {
        List<String> setters = Arrays.stream(AuditLog.class.getMethods())
            .filter(m -> m.getName().startsWith("set") && Modifier.isPublic(m.getModifiers()))
            .map(Method::getName)
            .toList();

        assertThat(setters).as("public setters on AuditLog").isEmpty();
        // And Hibernate is told never to issue an UPDATE for it, whatever happens
        // to a loaded instance.
        assertThat(AuditLog.class.isAnnotationPresent(Immutable.class)).isTrue();
    }
}
