#!/bin/bash
set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

usage() {
    echo "Usage: $0 [OPTIONS]"
    echo ""
    echo "Options:"
    echo "  --job <backfill|cdc>    Type of Flink job to run (required)"
    echo "  --jar <path>            Path to job JAR (default: flink-job/job.jar)"
    echo "  --config <path>         Path to config YAML (default: config/config.yaml)"
    echo "  --namespace <ns>        Kubernetes namespace (default: iotdb-dev)"
    echo "  --help                  Show this help message"
    echo ""
    echo "Examples:"
    echo "  $0 --job backfill"
    echo "  $0 --job cdc --jar target/my-job.jar"
    echo ""
}

# Default values
JOB_TYPE="backfill"
JAR_PATH="flink-job/job.jar"
CONFIG_PATH="config/config.yaml"
NAMESPACE="iotdb-dev"

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --job)
            JOB_TYPE="$2"
            shift 2
            ;;
        --jar)
            JAR_PATH="$2"
            shift 2
            ;;
        --config)
            CONFIG_PATH="$2"
            shift 2
            ;;
        --namespace)
            NAMESPACE="$2"
            shift 2
            ;;
        --help)
            usage
            exit 0
            ;;
        *)
            echo -e "${RED}Unknown option: $1${NC}"
            usage
            exit 1
            ;;
    esac
done

# Validate job type
if [[ "$JOB_TYPE" != "backfill" && "$JOB_TYPE" != "cdc" ]]; then
    echo -e "${RED}Error: Job type must be 'backfill' or 'cdc'${NC}"
    exit 1
fi

# Get JobManager pod name
JOBMANAGER_POD=$(kubectl get pod -n "$NAMESPACE" -l app=flink,component=jobmanager -o jsonpath='{.items[0].metadata.name}')

if [[ -z "$JOBMANAGER_POD" ]]; then
    echo -e "${RED}Error: No Flink JobManager pod found in namespace '$NAMESPACE'${NC}"
    exit 1
fi

echo -e "${GREEN}Using JobManager pod: ${JOBMANAGER_POD}${NC}"
echo ""

# Check if JAR exists
if [[ ! -f "$JAR_PATH" ]]; then
    echo -e "${RED}Error: JAR file not found: $JAR_PATH${NC}"
    exit 1
fi

# Check if config exists (optional for backfill, required for CDC)
if [[ ! -f "$CONFIG_PATH" ]]; then
    if [[ "$JOB_TYPE" == "cdc" ]]; then
        echo -e "${RED}Error: Config file not found: $CONFIG_PATH${NC}"
        exit 1
    fi
    echo -e "${YELLOW}Warning: Config file not found: $CONFIG_PATH (backfill will use job defaults)${NC}"
fi

# Step 1: Copy files to JobManager
echo -e "${YELLOW}Step 1: Copying files to JobManager...${NC}"
kubectl cp "$JAR_PATH" "$NAMESPACE/$JOBMANAGER_POD:/opt/flink/usrlib/job.jar"
echo -e "${GREEN}  Copied $JAR_PATH${NC}"

if [[ -f "$CONFIG_PATH" ]]; then
    kubectl cp "$CONFIG_PATH" "$NAMESPACE/$JOBMANAGER_POD:/opt/flink/usrlib/config.yaml"
    echo -e "${GREEN}  Copied $CONFIG_PATH${NC}"
fi

# Step 2: Submit the job
echo ""
echo -e "${YELLOW}Step 2: Submitting $JOB_TYPE job...${NC}"

if [[ "$JOB_TYPE" == "backfill" ]]; then
    kubectl exec -n "$NAMESPACE" "$JOBMANAGER_POD" -- \
        /opt/flink/bin/flink run -m localhost:8081 \
        -c com.example.MySQLToIoTDBBackfillJob \
        /opt/flink/usrlib/job.jar \
        /opt/flink/usrlib/config.yaml
else
    kubectl exec -n "$NAMESPACE" "$JOBMANAGER_POD" -- \
        /opt/flink/bin/flink run -m localhost:8081 \
        -c com.example.MySQLToIoTDBCdcJob \
        /opt/flink/usrlib/job.jar \
        /opt/flink/usrlib/config.yaml
fi

echo ""
echo -e "${GREEN}Job submitted successfully!${NC}"
echo ""
echo "Check the job status in Flink Web UI: http://localhost:8081"
