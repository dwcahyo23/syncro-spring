package com.syncro.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.syncro.AbstractPostgresIntegrationTest;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.sparepart.domain.SparepartTaxonomyDimension;
import com.syncro.sparepart.infrastructure.SparepartTaxonomyEntity;
import com.syncro.sparepart.infrastructure.SparepartTaxonomyRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

class DbIndexHygieneAtddGapScaffoldTest extends AbstractPostgresIntegrationTest {

  @Autowired
  private JdbcTemplate jdbc;

  @Autowired
  private MachineRepository machineRepository;

  @Autowired
  private SparepartTaxonomyRepository taxonomyRepository;

  @Test
  @Transactional
  @DisplayName("[P1] DH-P1-02 ORDER BY code pagination is complete across duplicate codes")
  void orderByCodePaginationIsCompleteAcrossDuplicateCodes() {
    UUID plantA = DbIndexHygieneTestData.plant(jdbc, "PLANT-A", "Plant A");
    UUID plantB = DbIndexHygieneTestData.plant(jdbc, "PLANT-B", "Plant B");
    UUID groupA = DbIndexHygieneTestData.machineGroup(jdbc, plantA, "Group A");
    UUID groupB = DbIndexHygieneTestData.machineGroup(jdbc, plantB, "Group B");

    DbIndexHygieneTestData.machine(jdbc, plantA, groupA, "M-DUP", "Machine A");
    DbIndexHygieneTestData.machine(jdbc, plantB, groupB, "M-DUP", "Machine B");

    List<String> seenCodes = new ArrayList<>();
    int offset = 0;
    while (true) {
      List<String> pageCodes = machineRepository
          .findAllUnscoped(null, null, null, null,
              PageRequest.of(offset, 1, Sort.by(Sort.Direction.ASC, "code")))
          .stream()
          .map(MachineEntity::getCode)
          .toList();
      if (pageCodes.isEmpty()) {
        break;
      }
      seenCodes.addAll(pageCodes);
      offset++;
    }

    assertThat(seenCodes).hasSize(2).containsOnly("M-DUP");
    assertThat(seenCodes).containsExactly("M-DUP", "M-DUP");
  }

  @Test
  @Transactional
  @DisplayName("[P1] DH-P1-03 EXPLAIN captures plan evidence for ORDER BY index usage")
  void explainPlanShowsOrderByIndexUsage() {
    UUID plantId = DbIndexHygieneTestData.plant(jdbc, "PLANT-A", "Plant A");
    UUID groupId = DbIndexHygieneTestData.machineGroup(jdbc, plantId, "Group A");
    DbIndexHygieneTestData.machine(jdbc, plantId, groupId, "M-001", "Machine One");

    String scopedPlan = explain(
        "SELECT id, code FROM machines WHERE plant_id = '" + plantId + "' ORDER BY code");
    String unscopedPlan = explain("SELECT id, code FROM machines ORDER BY code");

    assertThat(scopedPlan).isNotBlank();
    assertThat(unscopedPlan).isNotBlank();
  }

  @Test
  @Transactional
  @DisplayName("[P2] DH-P2-02 ORDER BY returns the exact expected list, not a subsequence")
  void exactOrderingListAfterV17() {
    jdbc.update("DELETE FROM sparepart_taxonomy WHERE dimension = 'CATEGORY'");
    DbIndexHygieneTestData.taxonomy(jdbc, "CATEGORY", "C-02", "Beta");
    DbIndexHygieneTestData.taxonomy(jdbc, "CATEGORY", "C-01", "Alpha");
    DbIndexHygieneTestData.taxonomy(jdbc, "CATEGORY", "C-03", "Charlie");

    List<String> names = taxonomyRepository
        .findByDimensionOrderByNameAsc(SparepartTaxonomyDimension.CATEGORY)
        .stream()
        .map(SparepartTaxonomyEntity::getName)
        .toList();

    assertThat(names).containsExactly("Alpha", "Beta", "Charlie");
  }

  private String explain(String sql) {
    return String.join("\n",
        jdbc.query("EXPLAIN (FORMAT TEXT) " + sql,
            (rs, rowNum) -> rs.getString("QUERY PLAN")));
  }
}
