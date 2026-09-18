package com.bloodlink.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import org.hibernate.annotations.Immutable;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.CrudRepository;

import com.bloodlink.domain.AuditLog;
import com.bloodlink.repository.AuditLogRepository;

/**
 * The code-level half of Business Rule 17's "append-only": nothing in Java offers a
 * way to change or remove an audit entry. (The database-level half — V12's
 * triggers — and the HTTP-level half are proven in AdminEditAndAuditLogIT.)
 *
 * These look like tests of how the code is written rather than what it does, and
 * they are, on purpose. The easiest way to break an append-only log is not a bug —
 * it is someone switching the repository to JpaRepository "for consistency" and
 * inheriting deleteAll() without noticing. These fail the build the moment that
 * happens, with a message saying why it matters.
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
