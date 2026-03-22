#!/bin/bash
# ProcessM Hierarchical Test Runner
# Runs each test individually to avoid Neo4j crash under load.
# Reports pass/fail for each test.

PASS=0
FAIL=0
SKIP=0
declare -a FAIL_DETAILS

run_test() {
  local class_method="$1"
  local label="${2:-$1}"

  # Run test
  OUTPUT=$(./gradlew test --tests "$class_method" --continue 2>&1)

  # Parse result from Gradle output
  if echo "$OUTPUT" | grep -q " PASSED"; then
    echo "✅  $label"
    PASS=$((PASS+1))
  elif echo "$OUTPUT" | grep -q " SKIPPED"; then
    echo "⏭  $label (skipped)"
    SKIP=$((SKIP+1))
  elif echo "$OUTPUT" | grep -q "ServiceUnavailable\|Unable to establish connection"; then
    echo "⚠  $label (Neo4j down — restarting...)"
    docker-compose restart neo4j
    sleep 20
    # Retry once
    OUTPUT=$(./gradlew test --tests "$class_method" --continue 2>&1)
    if echo "$OUTPUT" | grep -q " PASSED"; then
      echo "✅  $label (passed after restart)"
      PASS=$((PASS+1))
    else
      echo "❌  $label (still failing after restart)"
      FAIL=$((FAIL+1))
      local err
      err=$(echo "$OUTPUT" | grep -E "AssertionError|expected:|but was:|Exception" | head -2 | tr '\n' ' ')
      FAIL_DETAILS+=("❌ $label | $err")
    fi
  else
    echo "❌  $label"
    FAIL=$((FAIL+1))
    local err
    err=$(echo "$OUTPUT" | grep -E "AssertionError|expected:|but was:|Exception" | head -2 | tr '\n' ' ')
    FAIL_DETAILS+=("❌ $label | $err")
  fi
}

cd "$(dirname "$0")/.." || exit 1

echo "================================================="
echo " ProcessM Hierarchical Test Suite"
echo "================================================="
echo ""

echo "--- SelectQueryTests (all methods) ---"
run_test "*.hierarchical.SelectQueryTests" "SelectQueryTests (all)"

echo ""
echo "--- WhereQueryTests (all methods) ---"
run_test "*.hierarchical.WhereQueryTests" "WhereQueryTests (all)"

echo ""
echo "--- QueryTests ---"
run_test "*.hierarchical.QueryTests.limitSingleTest"
run_test "*.hierarchical.QueryTests.limitAllTest"
run_test "*.hierarchical.QueryTests.limits do not affect upper scopes"
run_test "*.hierarchical.QueryTests.limit applies independently per trace not globally"
run_test "*.hierarchical.QueryTests.limit with zero should return no results at that level"
run_test "*.hierarchical.QueryTests.limit with only event scope specified"
run_test "*.hierarchical.QueryTests.offsetSingleTest"
run_test "*.hierarchical.QueryTests.offsetAllTest"
run_test "*.hierarchical.QueryTests.offset with limit test"
run_test "*.hierarchical.QueryTests.orderBySimpleTest"
run_test "*.hierarchical.QueryTests.orderByWithModifierAndScopesTest"
run_test "*.hierarchical.QueryTests.orderByWithModifierAndScopes2Test"
run_test "*.hierarchical.QueryTests.selectEmpty"
run_test "*.hierarchical.QueryTests.hierarchy reconstruction test"
run_test "*.hierarchical.QueryTests.groupEventByStandardAttributeTest"
run_test "*.hierarchical.QueryTests.groupLogByEventStdAttrAndImplicitGroupEventByTest"
run_test "*.hierarchical.QueryTests.groupLogByEventStdAndGroupEventByStdAttrTest"
run_test "*.hierarchical.QueryTests.groupByImplicitScopeTest"
run_test "*.hierarchical.QueryTests.groupByOuterScopeTest"
run_test "*.hierarchical.QueryTests.groupByImplicitFromSelectTest"
run_test "*.hierarchical.QueryTests.groupByImplicitFromOrderByTest"
run_test "*.hierarchical.QueryTests.groupByImplicitWithHoistingTest"
run_test "*.hierarchical.QueryTests.groupByWithHoistingAndOrderByWithinGroupTest"
run_test "*.hierarchical.QueryTests.groupByWithHoistingAndOrderByCountTest"
run_test "*.hierarchical.QueryTests.aggregationFunctionIndependence"
run_test "*.hierarchical.QueryTests.groupByWithAndWithoutHoistingAndOrderByCountTest"
run_test "*.hierarchical.QueryTests.multiScopeGroupBy"
run_test "*.hierarchical.QueryTests.multiScopeImplicitGroupBy"
run_test "*.hierarchical.QueryTests.orderByExpressionTest"
run_test "*.hierarchical.QueryTests.missingAttributes"
run_test "*.hierarchical.QueryTests.orderByAggregationExpression"
run_test "*.hierarchical.QueryTests.errorHandlingTest"
run_test "*.hierarchical.QueryTests.readNestedAttributes"
run_test "*.hierarchical.QueryTests.skipNestedAttributes"
run_test "*.hierarchical.QueryTests.where on a nested attribute"
run_test "*.hierarchical.QueryTests.complex query with SELECT WHERE ORDER LIMIT"
run_test "*.hierarchical.QueryTests.query with all standard attributes test"

echo ""
echo "================================================="
echo " RESULTS: ✅ $PASS passed  ❌ $FAIL failed  ⏭ $SKIP skipped"
echo "================================================="

if [ ${#FAIL_DETAILS[@]} -gt 0 ]; then
  echo ""
  echo "--- FAILURES ---"
  for detail in "${FAIL_DETAILS[@]}"; do
    echo "$detail"
  done
fi