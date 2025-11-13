#!/bin/bash

# Decode and save MS365 credentials JSON file
echo $MS365_CREDENTIALS | base64 -d > ~/.ms365-credentials.json

# Extract and export environment variables from the credentials file
export MS365_TENANT_ID=$(cat ~/.ms365-credentials.json | jq -r '.tenantId')
export MS365_CLIENT_ID=$(cat ~/.ms365-credentials.json | jq -r '.clientId')
export MS365_CLIENT_SECRET=$(cat ~/.ms365-credentials.json | jq -r '.clientSecret')
export MS365_DRIVE_ID=$(cat ~/.ms365-credentials.json | jq -r '.driveId')

# Make them available to subsequent steps in the workflow
echo "MS365_TENANT_ID=$MS365_TENANT_ID" >> $GITHUB_ENV
echo "MS365_CLIENT_ID=$MS365_CLIENT_ID" >> $GITHUB_ENV
echo "MS365_CLIENT_SECRET=$MS365_CLIENT_SECRET" >> $GITHUB_ENV
echo "MS365_DRIVE_ID=$MS365_DRIVE_ID" >> $GITHUB_ENV
