// --- API & UI Helpers ---
const API_BASE = '/api';

function showToast(message, type = 'info') {
    const container = document.getElementById('toastContainer');
    const toast = document.createElement('div');
    toast.className = `toast ${type}`;
    toast.innerHTML = `<span>${message}</span>`;

    // Close button
    const closeBtn = document.createElement('button');
    closeBtn.innerHTML = '×';
    closeBtn.style.background = 'none';
    closeBtn.style.border = 'none';
    closeBtn.style.color = 'inherit';
    closeBtn.style.fontSize = '1.2rem';
    closeBtn.style.padding = '0';
    closeBtn.style.marginLeft = '1rem';
    closeBtn.style.cursor = 'pointer';
    closeBtn.style.boxShadow = 'none';
    closeBtn.onclick = () => {
        toast.style.animation = 'fadeOut 0.3s ease-out';
        setTimeout(() => toast.remove(), 300);
    };
    toast.appendChild(closeBtn);

    container.appendChild(toast);

    // Auto remove after 5 seconds
    setTimeout(() => {
        if (toast.parentElement) {
            toast.style.animation = 'fadeOut 0.3s ease-out';
            setTimeout(() => toast.remove(), 300);
        }
    }, 5000);
}

function setLoading(element, isLoading, loadingText = 'Loading...') {
    if (isLoading) {
        element.dataset.originalText = element.innerHTML;
        element.innerHTML = `<span class="spinner"></span> ${loadingText}`;
        element.disabled = true;
    } else {
        element.innerHTML = element.dataset.originalText || element.innerHTML;
        element.disabled = false;
    }
}

async function apiCall(endpoint, options = {}, outputElement) {
    outputElement.style.color = 'var(--text-color)';
    outputElement.textContent = 'Executing...';
    try {
        const response = await fetch(`${API_BASE}${endpoint}`, options);
        const resultText = await response.text();
        let formattedResult;
        try {
            const jsonResult = JSON.parse(resultText);
            formattedResult = JSON.stringify(jsonResult, null, 2);
        } catch (e) {
            formattedResult = resultText;
        }
        outputElement.textContent = `Status: ${response.status} ${response.statusText}\n\n${formattedResult}`;
        if (!response.ok) {
            outputElement.style.color = 'var(--red-color)';
        }
        return JSON.parse(resultText);
    } catch (error) {
        outputElement.style.color = 'var(--red-color)';
        outputElement.textContent = `Error: ${error.message}`;
        throw error;
    }
}

function openTab(tabName) {
    document.querySelectorAll('.tab-content').forEach(tab => tab.classList.remove('active'));
    document.querySelectorAll('.tab-button').forEach(btn => btn.classList.remove('active'));
    document.getElementById(tabName).classList.add('active');
    document.querySelector(`.tab-button[onclick="openTab('${tabName}')"]`).classList.add('active');
}

// --- Log Management ---
const logList = document.getElementById('logList');
const uploadOutput = document.getElementById('uploadOutput');
const activeLogSelect = document.getElementById('activeLogSelect');
const compareLogSelect = document.getElementById('compareLogSelect');

async function refreshLogs() {
    try {
        const logs = await apiCall('/logs', {}, uploadOutput);
        logList.innerHTML = '';
        activeLogSelect.innerHTML = '';
        if (compareLogSelect) compareLogSelect.innerHTML = '';

        if (logs && logs.length > 0) {
            logs.forEach(log => {
                const logId = log.logId;
                const listItem = document.createElement('div');
                listItem.className = 'log-list-item';
                listItem.innerHTML = `
        <span><strong>${log.name || logId}</strong> (${logId})</span>
        <div class="log-actions">
            <button onclick="getLogStatistics('${logId}')">📊 Stats</button>
            <button class="danger" onclick="deleteLog('${logId}')">🗑️ Delete</button>
        </div>
        `;
                logList.appendChild(listItem);

                const option = document.createElement('option');
                option.value = logId;
                option.textContent = `${log.name || logId} (${logId})`;
                activeLogSelect.appendChild(option.cloneNode(true));
                if (compareLogSelect) compareLogSelect.appendChild(option);
            });
        } else {
            logList.innerHTML = '<p>No logs found. Load Hospital Log to get started!</p>';
        }
    } catch (error) {
        console.error("Failed to refresh logs", error);
    }
}

async function getLogStatistics(logId) {
    await apiCall(`/logs/${logId}/statistics`, {}, uploadOutput);
}

async function deleteLog(logId) {
    if (confirm(`Are you sure you want to delete log "${logId}"?`)) {
        try {
            await apiCall(`/logs/${logId}?deleteAllData=true`, { method: 'DELETE' }, uploadOutput);
            showToast(`Log "${logId}" deleted`, 'success');
            refreshLogs();
        } catch (e) {
            showToast('Failed to delete log: ' + e.message, 'error');
        }
    }
}

document.getElementById('refreshLogsButton').addEventListener('click', refreshLogs);

document.getElementById('uploadButton').addEventListener('click', async () => {
    const fileInput = document.getElementById('xesFileInput');
    const btn = document.getElementById('uploadButton');

    if (fileInput.files.length === 0) {
        showToast('Please select a file first.', 'error');
        return;
    }

    setLoading(btn, true, 'Uploading...');
    const formData = new FormData();
    formData.append('file', fileInput.files[0]);

    try {
        await apiCall('/logs/upload', { method: 'POST', body: formData }, uploadOutput);
        showToast('Log uploaded successfully!', 'success');
        refreshLogs();
    } catch (e) {
        showToast('Upload failed: ' + e.message, 'error');
    } finally {
        setLoading(btn, false);
    }
});

document.getElementById('loadSampleButton').addEventListener('click', async () => {
    const btn = document.getElementById('loadSampleButton');
    setLoading(btn, true, 'Loading...');
    uploadOutput.textContent = 'Loading Hospital Log (81MB)... This may take 1-3 minutes...';

    const params = new URLSearchParams({
        resourcePath: 'logs/Hospital_log.xes',
        logId: 'Hospital_log'
    });

    try {
        await apiCall(`/logs/load-sample?${params}`, { method: 'POST' }, uploadOutput);
        showToast('Hospital log loaded successfully!', 'success');
        refreshLogs();
    } catch (e) {
        showToast('Failed to load sample: ' + e.message, 'error');
    } finally {
        setLoading(btn, false);
    }
});

document.getElementById('loadSampleButton2').addEventListener('click', async () => {
    const btn = document.getElementById('loadSampleButton2');
    setLoading(btn, true, 'Loading...');
    const params = new URLSearchParams({
        resourcePath: 'logs/sample_process.xes',
        logId: 'sample_process'
    });
    try {
        await apiCall(`/logs/load-sample?${params}`, { method: 'POST' }, uploadOutput);
        showToast('Sample process log loaded successfully!', 'success');
        refreshLogs();
    } catch (e) {
        showToast('Failed to load sample: ' + e.message, 'error');
    } finally {
        setLoading(btn, false);
    }
});

// --- PQL Query ---
const pqlButton = document.getElementById('pqlButton');
const pqlQueryText = document.getElementById('pqlQueryText');
const pqlOutput = document.getElementById('pqlOutput');
const pqlQuerySelect = document.getElementById('pqlQuerySelect');

// Curated sample queries for Hospital_log.xes - guaranteed to work!
const sampleQueries = {
    // === BASICS - Show Different Scopes ===
    "🔹 Show First 20 Events": "select event:* limit l:20",
    "🔹 Show First 10 Traces": "select trace:* limit l:10",
    "🔹 Show Log Attributes": "select log:*",
    "🔹 Mixed Scope (Trace ID + Event Activity)": "select trace:caseId, event:name limit l:20",
    "🔹 Hoisting (Event -> Trace)": "select event:name, ^event:caseId limit l:20",

    // === SIMPLE FILTERS ===
    "🏥 Events from Radiology Department": "select event:name, event:timestamp, event:org_group where event:org_group = 'Radiology' limit l:30",
    "🏥 Events from General Lab": "select event:name, event:timestamp where event:org_group = 'General Lab Clinical Chemistry' limit l:25",
    "🏥 Events from Operating Rooms": "select event:name, event:org_group where event:org_group = 'Operating rooms' limit l:30",

    // === AGGREGATIONS - Show Power of PQL ===
    "📊 Count All Events": "select count(event:id)",
    "📊 Events per Department (Top 10)": "select event:org_group, count(event:id) group by event:org_group order by count(event:id) desc limit l:10",
    "📊 Top 15 Most Frequent Activities": "select event:name, count(event:id) group by event:name order by count(event:id) desc limit l:15",

    // === ADVANCED - Multiple Departments ===
    "🔬 Compare 3 Departments": "select event:org_group, count(event:id) where event:org_group in ('Radiology', 'Radiotherapy', 'Nuclear Medicine') group by event:org_group order by count(event:id) desc",

    // === SHOWCASE QUERIES ===
    "⭐ All Departments (Sorted)": "select event:org_group group by event:org_group order by event:org_group asc",
    "⭐ Recent Events (Latest First)": "select event:name, event:org_group, event:timestamp order by event:timestamp desc limit l:25"
};

function populateSampleQueries() {
    for (const [name, query] of Object.entries(sampleQueries)) {
        const option = document.createElement('option');
        option.value = query;
        option.textContent = name;
        pqlQuerySelect.appendChild(option);
    }
}

pqlQuerySelect.addEventListener('change', (e) => {
    if (e.target.value) {
        pqlQueryText.value = e.target.value;
    }
});

function renderQueryResults(data, container) {
    container.innerHTML = '';

    if (!data.success) {
        container.style.color = 'var(--red-color)';
        container.textContent = `Error: ${data.error || 'Query failed'}`;
        return;
    }

    // Stats section
    const statsDiv = document.createElement('div');
    statsDiv.className = 'query-stats';
    statsDiv.innerHTML = `
    <div class="stat-item">
        <span class="stat-label">Results</span>
        <span class="stat-value">${data.resultCount}</span>
    </div>
    <div class="stat-item">
        <span class="stat-label">Execution Time</span>
        <span class="stat-value">${data.executionTimeMs}ms</span>
    </div>
    <div class="stat-item">
        <span class="stat-label">Query Type</span>
        <span class="stat-value">${data.query.split(' ')[0].toUpperCase()}</span>
    </div>
    `;
    container.appendChild(statsDiv);

    // Action buttons
    const actionsDiv = document.createElement('div');
    actionsDiv.className = 'action-buttons';
    actionsDiv.innerHTML = `
    <button class="secondary" onclick="exportToCSV()">📥 Export to CSV</button>
    <button class="secondary" onclick="exportToXES(false)">📥 Export to XES</button>
    <button class="secondary" onclick="exportToXES(true)">📥 Export to XES (gzip)</button>
    <button class="secondary" onclick="copyToClipboard()">📋 Copy JSON</button>
    <button class="secondary" onclick="toggleRawView()">🔄 Toggle Raw View</button>
    `;
    container.appendChild(actionsDiv);

    // Cypher query (collapsible)
    const cypherSection = document.createElement('div');
    cypherSection.innerHTML = `
    <div class="toggle-section" onclick="toggleCollapsible(this)">
        <span>🔧 Generated Cypher Query</span>
        <span>▼</span>
    </div>
    <div class="collapsible-content">
        <pre
            style="margin: 0.5rem 0; padding: 0.5rem; background-color: var(--input-bg-color); border-radius: 4px;">${data.cypherQuery || 'N/A'}</pre>
    </div>
    `;
    container.appendChild(cypherSection);

    // Results table or raw view
    const resultsDiv = document.createElement('div');
    resultsDiv.id = 'resultsContainer';

    if (data.results && data.results.length > 0) {
        const tableContainer = document.createElement('div');
        tableContainer.className = 'table-container';
        tableContainer.id = 'tableView';

        const table = document.createElement('table');
        table.className = 'results-table';

        // Get all unique keys from results
        const allKeys = new Set();
        data.results.forEach(row => {
            Object.keys(row).forEach(key => allKeys.add(key));
        });
        const columns = Array.from(allKeys);

        // Header
        const thead = document.createElement('thead');
        const headerRow = document.createElement('tr');
        columns.forEach(col => {
            const th = document.createElement('th');
            th.textContent = col;
            headerRow.appendChild(th);
        });
        thead.appendChild(headerRow);
        table.appendChild(thead);

        // Body
        const tbody = document.createElement('tbody');
        data.results.forEach(row => {
            const tr = document.createElement('tr');
            columns.forEach(col => {
                const td = document.createElement('td');
                const value = row[col];

                // Format complex values
                if (value === null || value === undefined) {
                    td.textContent = 'NULL';
                    td.style.color = 'var(--red-color)';
                } else if (typeof value === 'object') {
                    td.textContent = JSON.stringify(value);
                    td.style.fontSize = '0.8rem';
                } else {
                    td.textContent = value;
                }
                tr.appendChild(td);
            });
            tbody.appendChild(tr);
        });
        table.appendChild(tbody);

        tableContainer.appendChild(table);
        resultsDiv.appendChild(tableContainer);

        // Raw view (hidden by default)
        const rawView = document.createElement('pre');
        rawView.id = 'rawView';
        rawView.style.display = 'none';
        rawView.textContent = JSON.stringify(data.results, null, 2);
        resultsDiv.appendChild(rawView);
    } else {
        resultsDiv.innerHTML = '<p style="color: var(--yellow-color);">No results found.</p>';
    }

    container.appendChild(resultsDiv);

    // Store data globally for export
    window.currentQueryResults = data;
}

function toggleCollapsible(element) {
    const content = element.nextElementSibling;
    const arrow = element.querySelector('span:last-child');
    content.classList.toggle('open');
    arrow.textContent = content.classList.contains('open') ? '▲' : '▼';
}

function toggleRawView() {
    const tableView = document.getElementById('tableView');
    const rawView = document.getElementById('rawView');
    if (tableView && rawView) {
        const isTableVisible = tableView.style.display !== 'none';
        tableView.style.display = isTableVisible ? 'none' : 'block';
        rawView.style.display = isTableVisible ? 'block' : 'none';
    }
}

function exportToCSV() {
    if (!window.currentQueryResults || !window.currentQueryResults.results) {
        showToast('No data to export', 'error');
        return;
    }

    const data = window.currentQueryResults.results;
    if (data.length === 0) return;

    const headers = Object.keys(data[0]);
    let csv = headers.join(',') + '\n';

    data.forEach(row => {
        const values = headers.map(header => {
            const value = row[header];
            if (value === null || value === undefined) return '';
            if (typeof value === 'object') return JSON.stringify(value);
            return `"${String(value).replace(/"/g, '""')}"`;
        });
        csv += values.join(',') + '\n';
    });

    const blob = new Blob([csv], { type: 'text/csv' });
    const url = window.URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `pql_results_${Date.now()}.csv`;
    a.click();
    window.URL.revokeObjectURL(url);
}

async function exportToXES(compress = false) {
    const logId = activeLogSelect.value;
    const query = pqlQueryText.value;

    if (!logId) {
        showToast('Please select an active log first.', 'error');
        return;
    }

    if (!query) {
        showToast('Please enter a PQL query first.', 'error');
        return;
    }

    try {
        // Show loading indicator
        const originalText = pqlOutput.textContent;
        pqlOutput.textContent = `Exporting to XES${compress ? ' (compressed)' : ''}...`;
        pqlOutput.style.color = 'var(--text-color)';
        showToast(`Starting XES export${compress ? ' (compressed)' : ''}...`, 'info');

        // Prepare request URL with query parameters
        const params = new URLSearchParams({
            compress: compress.toString(),
            logName: `PQL Query Result - ${new Date().toISOString()}`
        });

        // Make request to execute-xes endpoint
        const response = await fetch(`${API_BASE}/query/execute-xes?${params}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ logId, query })
        });

        if (!response.ok) {
            throw new Error(`Server returned ${response.status}: ${response.statusText}`);
        }

        // Get the blob from response
        const blob = await response.blob();

        // Get filename from Content-Disposition header or generate one
        const contentDisposition = response.headers.get('Content-Disposition');
        let filename = `query_result_${Date.now()}.${compress ? 'xes.gz' : 'xes'}`;

        if (contentDisposition) {
            const filenameMatch = contentDisposition.match(/filename="?([^"]+)"?/);
            if (filenameMatch) {
                filename = filenameMatch[1];
            }
        }

        // Create download link
        const url = window.URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = filename;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        window.URL.revokeObjectURL(url);

        // Show success message
        pqlOutput.textContent = originalText;
        showToast(`XES file downloaded successfully: ${filename}`, 'success');

    } catch (error) {
        pqlOutput.style.color = 'var(--red-color)';
        pqlOutput.textContent = `Error exporting to XES: ${error.message}`;
        showToast(`Failed to export to XES: ${error.message}`, 'error');
    }
}

function copyToClipboard() {
    if (!window.currentQueryResults) {
        showToast('No data to copy', 'error');
        return;
    }

    const text = JSON.stringify(window.currentQueryResults, null, 2);
    navigator.clipboard.writeText(text).then(() => {
        showToast('Copied to clipboard!', 'success');
    }).catch(err => {
        console.error('Failed to copy:', err);
        showToast('Failed to copy to clipboard', 'error');
    });
}

pqlButton.addEventListener('click', async () => {
    const logId = activeLogSelect.value;
    const query = pqlQueryText.value;
    if (!logId) {
        showToast('Please select an active log first.', 'error');
        return;
    }

    setLoading(pqlButton, true, 'Running...');
    pqlOutput.textContent = 'Executing query...';
    pqlOutput.style.color = 'var(--text-color)';

    try {
        const response = await fetch(`${API_BASE}/query/execute`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ logId, query })
        });

        const data = await response.json();
        renderQueryResults(data, pqlOutput);
        if (data.success) {
            showToast(`Query executed in ${data.executionTimeMs}ms`, 'success');
        } else {
            showToast('Query failed', 'error');
        }
    } catch (error) {
        pqlOutput.style.color = 'var(--red-color)';
        pqlOutput.textContent = `Error: ${error.message}`;
        showToast(`Error: ${error.message}`, 'error');
    } finally {
        setLoading(pqlButton, false);
    }
});

// --- Editor Helper ---
function insertAtCursor(text) {
    const editor = document.getElementById('pqlQueryText');
    if (!editor) return;

    const startPos = editor.selectionStart;
    const endPos = editor.selectionEnd;
    const value = editor.value;

    editor.value = value.substring(0, startPos) + text + value.substring(endPos, value.length);

    // Move cursor to end of inserted text
    editor.selectionStart = editor.selectionEnd = startPos + text.length;
    editor.focus();
}

// --- Verification ---
// --- Verification ---
const verifyButtonNew = document.getElementById('verifyButtonNew');
const verificationResultNew = document.getElementById('verificationResultNew');
const compareQueryText = document.getElementById('compareQueryText');

// Sample Queries Listener
const sampleQueriesSelect = document.getElementById('sampleQueriesSelect');
if (sampleQueriesSelect) {
    sampleQueriesSelect.addEventListener('change', (e) => {
        if (e.target.value) {
            compareQueryText.value = e.target.value;
        }
    });
}

if (verifyButtonNew) {
    verifyButtonNew.addEventListener('click', async () => {
        const logId = compareLogSelect.value;
        // Get text of selected option for name heuristic
        const logNameText = compareLogSelect.options[compareLogSelect.selectedIndex]?.text || logId;

        const query = compareQueryText.value;

        if (!logId) {
            showToast('Please select a log first', 'error');
            return;
        }

        if (!query) {
            showToast('Please enter a query to verify', 'error');
            return;
        }

        const includeTraces = document.getElementById('includeTracesCheck')?.checked || false;
        const includeEvents = document.getElementById('includeEventsCheck')?.checked || false;

        setLoading(verifyButtonNew, true, 'Verifying...');
        verificationResultNew.innerHTML = '';

        try {
            const response = await fetch(`${API_BASE}/query/verify`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    logId: logId,
                    logName: logNameText,
                    query: query,
                    includeTraces: includeTraces,
                    includeEvents: includeEvents
                })
            });

            const data = await response.json();
            renderVerificationResult(data);

            if (data.match) {
                showToast('Verification successful: Results Match', 'success');
            } else {
                showToast('Verification mismatch', 'error');
            }

        } catch (error) {
            showToast('Verification failed: ' + error.message, 'error');
            verificationResultNew.innerHTML = `<div style="color:red; padding: 1rem;">Error: ${error.message}</div>`;
        } finally {
            setLoading(verifyButtonNew, false);
        }
    });
}

function renderVerificationResult(data) {
    const matchClass = data.match ? 'match-success' : 'match-fail';
    const matchText = data.match ? 'MATCH' : 'MISMATCH';

    const html = `
        <div class="verification-box">
            <div class="verification-header">
                <span>Verification with ProcessM</span>
                <span class="match-badge ${matchClass}">${matchText}</span>
            </div>
            <div class="verification-content">
                <div class="verification-col">
                    <h4>Local Execution</h4>
                    <div class="verification-detail">
                        <div>Success: ${data.localSuccess}</div>
                        <div>Rows: ${data.localCount}</div>
                    </div>
                </div>
                <div class="verification-col">
                    <h4>Remote ProcessM</h4>
                    <div class="verification-detail">
                        <div>Success: ${data.remoteSuccess}</div>
                        <div>Rows: ${data.remoteCount}</div>
                    </div>
                </div>
            </div>

            <div class="verification-content" style="padding-top: 0; padding-bottom: 0;">
                 <div class="verification-col" style="grid-column: span 2;">
                    <h4>Remote Request Details</h4>
                    <div class="verification-detail" style="font-size: 0.8rem; min-height: auto;">
                        <div style="margin-bottom: 0.5rem;"><strong>Remote Log ID:</strong> ${data.remoteLogId || 'Unknown'}</div>
                        <div style="margin-bottom: 0.5rem;"><strong>Adapted Query:</strong> ${data.remoteAdaptedQuery || 'N/A'}</div>
                        <div style="word-break: break-all;"><strong>URL:</strong> ${data.remoteRequestUrl || 'N/A'}</div>
                    </div>
                 </div>
            </div>
            
             <div class="verification-content" style="padding-top: 1rem;">
                <div class="verification-col">
                    <h4>Local Output</h4>
                    <div id="local-results-viewer" style="max-height: 400px; overflow: auto; background: #111; padding: 1rem; border-radius: 8px;"></div>
                </div>
                <div class="verification-col">
                    <h4>Remote Output</h4>
                     <div id="remote-results-viewer" style="max-height: 400px; overflow: auto; background: #111; padding: 1rem; border-radius: 8px;"></div>
                </div>
            </div>

            <div style="padding: 0 1.5rem 1.5rem; color: var(--text-secondary); font-size: 0.85rem; white-space: pre-wrap; font-family: monospace;">${data.details}</div>
        </div>
    `;

    verificationResultNew.innerHTML = html;

    // Initialize viewers
    if (typeof JsonViewer !== 'undefined') {
        const localViewer = new JsonViewer();
        if (data.localResults) {
            localViewer.render(data.localResults, document.getElementById('local-results-viewer'));
        } else {
            document.getElementById('local-results-viewer').textContent = 'No data';
        }

        const remoteViewer = new JsonViewer();
        if (data.remoteResults) {
            remoteViewer.render(data.remoteResults, document.getElementById('remote-results-viewer'));
        } else {
            document.getElementById('remote-results-viewer').textContent = 'No data';
        }
    } else {
        // Fallback if JsonViewer not loaded
        document.getElementById('local-results-viewer').innerHTML = `<pre>${JSON.stringify(data.localResults || {}, null, 2)}</pre>`;
        document.getElementById('remote-results-viewer').innerHTML = `<pre>${JSON.stringify(data.remoteResults || {}, null, 2)}</pre>`;
    }
}

// --- Initial Load ---
document.addEventListener('DOMContentLoaded', () => {
    openTab('logs');
    refreshLogs();
    populateSampleQueries();
});
