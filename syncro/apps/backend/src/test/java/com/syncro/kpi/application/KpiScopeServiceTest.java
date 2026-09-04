package com.syncro.kpi.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.org.application.OperationalScope;
import com.syncro.org.application.OperationalScopeService;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Story 20-1 review tests for {@link KpiScopeService}: the group-dimension merge
 * (leader groups + active-team groups) and the null-plantIds "sees all" semantics.
 * The org module tests scope DERIVATION; these pin the kpi-side consumption that
 * KpiQueryServiceTest stubs away.
 */
class KpiScopeServiceTest {

  @Test
  @DisplayName("20.1-SCOPE-001 groupIds merges leader groups and active-team groups, deduped")
  void groupIdsMergesBothDimensions() {
    var org = mock(OperationalScopeService.class);
    var service = new KpiScopeService(org);
    var groupA = UUID.randomUUID();
    var groupB = UUID.randomUUID();
    var scope = new OperationalScope(Set.of(UUID.randomUUID()), Set.of(groupA), Set.of(groupB, groupA));

    var merged = service.groupIds(scope);

    assertThat(merged).containsExactlyInAnyOrder(groupA, groupB);
  }

  @Test
  @DisplayName("20.1-SCOPE-002 groupIds tolerates null dimensions")
  void groupIdsToleratesNulls() {
    var org = mock(OperationalScopeService.class);
    var service = new KpiScopeService(org);

    assertThat(service.groupIds(new OperationalScope(null, null, null))).isEmpty();
  }

  @Test
  @DisplayName("20.1-SCOPE-003 plantVisible: null plantIds sees all, restricted set gates")
  void plantVisibleNullMeansAll() {
    var org = mock(OperationalScopeService.class);
    var service = new KpiScopeService(org);
    var plantA = UUID.randomUUID();
    var plantB = UUID.randomUUID();

    assertThat(service.plantVisible(new OperationalScope(null, Set.of(), Set.of()), plantA)).isTrue();
    assertThat(service.plantVisible(new OperationalScope(Set.of(plantA), Set.of(), Set.of()), plantA)).isTrue();
    assertThat(service.plantVisible(new OperationalScope(Set.of(plantA), Set.of(), Set.of()), plantB)).isFalse();
  }

  @Test
  @DisplayName("20.1-SCOPE-004 derive delegates to the org module's single scope service (AD-2)")
  void deriveDelegatesToOrgService() {
    var org = mock(OperationalScopeService.class);
    var service = new KpiScopeService(org);
    var user = new AuthenticatedUser(UUID.randomUUID().toString(), "leader@syncro.test",
        ApplicationRole.SECTION_LEADER);
    var expected = new OperationalScope(Set.of(UUID.randomUUID()), Set.of(UUID.randomUUID()), Set.of());
    when(org.derive(user)).thenReturn(expected);

    assertThat(service.derive(user)).isSameAs(expected);
  }
}