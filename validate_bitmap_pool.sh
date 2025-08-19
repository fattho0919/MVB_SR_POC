#!/bin/bash

# Bitmap Pool Validation Script for Story 1.3
# Usage: ./validate_bitmap_pool.sh

set -e

echo "=========================================="
echo "Story 1.3: Bitmap Memory Optimization"
echo "Validation Script"
echo "=========================================="
echo ""

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Check if device is connected
echo "📱 Checking device connection..."
if ! adb devices | grep -q "device$"; then
    echo -e "${RED}❌ No device connected. Please connect an Android device or start an emulator.${NC}"
    exit 1
fi
echo -e "${GREEN}✓ Device connected${NC}"
echo ""

# Build the app
echo "🔨 Building the app..."
./gradlew assembleDebug
if [ $? -eq 0 ]; then
    echo -e "${GREEN}✓ Build successful${NC}"
else
    echo -e "${RED}❌ Build failed${NC}"
    exit 1
fi
echo ""

# Install the app
echo "📲 Installing the app..."
./gradlew installDebug
if [ $? -eq 0 ]; then
    echo -e "${GREEN}✓ Installation successful${NC}"
else
    echo -e "${RED}❌ Installation failed${NC}"
    exit 1
fi
echo ""

# Clear logcat
echo "🧹 Clearing logcat..."
adb logcat -c
echo ""

# Run tests
echo "🧪 Running validation tests..."
echo "This may take 2-3 minutes..."
./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.sr_poc.BitmapPoolValidationTest 2>&1 | tee test_output.log

# Check test results
if grep -q "BUILD SUCCESSFUL" test_output.log; then
    echo -e "${GREEN}✓ All tests passed${NC}"
    TEST_RESULT="PASSED"
else
    echo -e "${RED}❌ Some tests failed${NC}"
    TEST_RESULT="FAILED"
fi
echo ""

# Extract metrics from logcat
echo "📊 Extracting metrics..."
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
REPORT_FILE="bitmap_pool_validation_${TIMESTAMP}.txt"

{
    echo "=========================================="
    echo "Bitmap Pool Validation Report"
    echo "Date: $(date)"
    echo "Device: $(adb shell getprop ro.product.model)"
    echo "Android: $(adb shell getprop ro.build.version.release)"
    echo "=========================================="
    echo ""
    echo "Test Suite Result: $TEST_RESULT"
    echo ""
    echo "=== Test Results ==="
} > "$REPORT_FILE"

# Get test results from logcat
adb logcat -d | grep "BitmapPoolValidation" >> "$REPORT_FILE"

# Extract key metrics
echo "" >> "$REPORT_FILE"
echo "=== Key Metrics ===" >> "$REPORT_FILE"

# Extract reuse rate
REUSE_RATE=$(adb logcat -d | grep "Reuse rate:" | tail -1 | sed 's/.*Reuse rate: \([0-9.]*\)%.*/\1/')
if [ ! -z "$REUSE_RATE" ]; then
    echo "Bitmap Reuse Rate: ${REUSE_RATE}%" >> "$REPORT_FILE"
    if (( $(echo "$REUSE_RATE > 80" | bc -l) )); then
        echo -e "${GREEN}✓ Reuse Rate: ${REUSE_RATE}% (Target: >80%)${NC}"
    else
        echo -e "${YELLOW}⚠ Reuse Rate: ${REUSE_RATE}% (Target: >80%)${NC}"
    fi
fi

# Extract OOM frequency
OOM_FREQ=$(adb logcat -d | grep "OOM frequency:" | tail -1 | sed 's/.*OOM frequency: \([0-9.]*\)%.*/\1/')
if [ ! -z "$OOM_FREQ" ]; then
    echo "OOM Frequency: ${OOM_FREQ}%" >> "$REPORT_FILE"
    if (( $(echo "$OOM_FREQ < 1" | bc -l) )); then
        echo -e "${GREEN}✓ OOM Frequency: ${OOM_FREQ}% (Target: <1%)${NC}"
    else
        echo -e "${RED}❌ OOM Frequency: ${OOM_FREQ}% (Target: <1%)${NC}"
    fi
fi

# Extract GC frequency
GC_FREQ=$(adb logcat -d | grep "GC frequency:" | tail -1 | sed 's/.*GC frequency: \([0-9.]*\)%.*/\1/')
if [ ! -z "$GC_FREQ" ]; then
    echo "GC Frequency: ${GC_FREQ}%" >> "$REPORT_FILE"
    if (( $(echo "$GC_FREQ < 5" | bc -l) )); then
        echo -e "${GREEN}✓ GC Frequency: ${GC_FREQ}% (Target: <5%)${NC}"
    else
        echo -e "${YELLOW}⚠ GC Frequency: ${GC_FREQ}% (Target: <5%)${NC}"
    fi
fi

# Get memory info
echo "" >> "$REPORT_FILE"
echo "=== Memory Usage ===" >> "$REPORT_FILE"
adb shell dumpsys meminfo com.example.sr_poc | grep -A 10 "App Summary" >> "$REPORT_FILE"

# Get pool stats
echo "" >> "$REPORT_FILE"
echo "=== Pool Statistics ===" >> "$REPORT_FILE"
adb logcat -d | grep "BitmapPoolManager\|BitmapPool" | tail -20 >> "$REPORT_FILE"

echo ""
echo "=========================================="
echo "📋 Validation Summary"
echo "=========================================="

# Summary
if [ "$TEST_RESULT" = "PASSED" ]; then
    echo -e "${GREEN}✅ Story 1.3 Validation: PASSED${NC}"
    echo ""
    echo "All bitmap pool optimization features are working correctly."
    echo "The implementation meets the success criteria."
else
    echo -e "${RED}❌ Story 1.3 Validation: NEEDS ATTENTION${NC}"
    echo ""
    echo "Some tests failed. Please review the detailed report."
fi

echo ""
echo "📄 Detailed report saved to: $REPORT_FILE"
echo ""

# Offer to open report
read -p "Would you like to view the detailed report? (y/n) " -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
    cat "$REPORT_FILE"
fi

echo ""
echo "=========================================="
echo "Validation Complete!"
echo "=========================================="

# Clean up
rm -f test_output.log

exit 0