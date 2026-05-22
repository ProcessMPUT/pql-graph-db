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

async function apiCall(endpoint, options = {}, outputElement = null) {
    if (outputElement) {
        outputElement.style.color = 'var(--text-color)';
        outputElement.textContent = 'Executing...';
    }
    try {
        const response = await fetch(`${API_BASE}${endpoint}`, options);
        const resultText = await response.text();
        const jsonResult = resultText.trim() ? JSON.parse(resultText) : null;
        let formattedResult;
        if (jsonResult !== null) {
            formattedResult = JSON.stringify(jsonResult, null, 2);
        } else {
            formattedResult = resultText;
        }
        if (outputElement) {
            outputElement.textContent = `Status: ${response.status} ${response.statusText}\n\n${formattedResult}`;
            if (!response.ok) {
                outputElement.style.color = 'var(--red-color)';
            }
        }
        return jsonResult;
    } catch (error) {
        if (outputElement) {
            outputElement.style.color = 'var(--red-color)';
            outputElement.textContent = `Error: ${error.message}`;
        }
        throw error;
    }
}

async function fetchJson(endpoint, options = {}) {
    const response = await fetch(`${API_BASE}${endpoint}`, options);
    const resultText = await response.text();
    const payload = resultText.trim() ? JSON.parse(resultText) : null;

    if (!response.ok) {
        const message = payload?.message || payload?.error || `${response.status} ${response.statusText}`;
        throw new Error(message);
    }

    return payload;
}

function openTab(tabName) {
    document.querySelectorAll('.tab-content').forEach(tab => tab.classList.remove('active'));
    document.querySelectorAll('.tab-button').forEach(btn => btn.classList.remove('active'));
    document.getElementById(tabName).classList.add('active');
    document.querySelector(`.tab-button[onclick="openTab('${tabName}')"]`).classList.add('active');
}

// --- Log Management ---
const logList = document.getElementById('logList');
const dataStoreLogList = document.getElementById('dataStoreLogList');
const activeDataStoreSummary = document.getElementById('activeDataStoreSummary');
const dataStoreNameInput = document.getElementById('dataStoreNameInput');
const createDataStoreButton = document.getElementById('createDataStoreButton');
const uploadOutput = document.getElementById('uploadOutput');
const logImportSelect = document.getElementById('logImportSelect');
const customLogFileGroup = document.getElementById('customLogFileGroup');
const chooseCustomLogButton = document.getElementById('chooseCustomLogButton');
const selectedCustomLogName = document.getElementById('selectedCustomLogName');
const activeLogSelect = document.getElementById('activeLogSelect');
const compareLogSelect = document.getElementById('compareLogSelect');
const remoteProcessMDataStoreSelect = document.getElementById('remoteProcessMDataStoreSelect');
const refreshRemoteProcessMStoresButton = document.getElementById('refreshRemoteProcessMStoresButton');
let currentDataStores = [];
let currentRemoteProcessMDataStores = [];

function getSelectedDataStoreId() {
    return activeLogSelect?.value || compareLogSelect?.value || '';
}

function setSelectedDataStore(dataStoreId) {
    if (activeLogSelect) activeLogSelect.value = dataStoreId;
    if (compareLogSelect) compareLogSelect.value = dataStoreId;
    renderDataStores();
    refreshSelectedDataStoreLogs();
}

function renderDataStoreOptions() {
    const previousActive = activeLogSelect?.value || '';
    const previousCompare = compareLogSelect?.value || previousActive;

    activeLogSelect.innerHTML = '';
    if (compareLogSelect) compareLogSelect.innerHTML = '';

    currentDataStores.forEach(store => {
        const option = document.createElement('option');
        option.value = store.id;
        option.textContent = `${store.name || store.id} (${store.id})`;
        activeLogSelect.appendChild(option.cloneNode(true));
        if (compareLogSelect) compareLogSelect.appendChild(option);
    });

    const fallbackId = currentDataStores[0]?.id || '';
    if (activeLogSelect) activeLogSelect.value = currentDataStores.some(s => s.id === previousActive) ? previousActive : fallbackId;
    if (compareLogSelect) compareLogSelect.value = currentDataStores.some(s => s.id === previousCompare) ? previousCompare : activeLogSelect?.value || fallbackId;
}

function renderDataStores() {
    logList.innerHTML = '';
    const selectedId = getSelectedDataStoreId();

    if (!currentDataStores.length) {
        logList.innerHTML = '<p>No data stores found.</p>';
        return;
    }

    currentDataStores.forEach(store => {
        const item = document.createElement('div');
        item.className = `log-list-item data-store-item${store.id === selectedId ? ' active' : ''}`;

        const meta = document.createElement('div');
        meta.className = 'data-store-meta';
        meta.innerHTML = `
            <strong>${store.name || store.id}</strong>
            <span>${store.id}</span>
            <small>Created: ${store.createdAt || 'unknown'}</small>
        `;

        const actions = document.createElement('div');
        actions.className = 'log-actions';

        const selectButton = document.createElement('button');
        selectButton.textContent = store.id === selectedId ? 'Active' : 'Use';
        selectButton.disabled = store.id === selectedId;
        selectButton.addEventListener('click', () => setSelectedDataStore(store.id));

        const renameButton = document.createElement('button');
        renameButton.className = 'secondary';
        renameButton.textContent = 'Rename';
        renameButton.addEventListener('click', () => renameDataStore(store.id, store.name || ''));

        const deleteButton = document.createElement('button');
        deleteButton.className = 'danger';
        deleteButton.textContent = 'Delete';
        deleteButton.addEventListener('click', () => deleteLog(store.id));

        actions.append(selectButton, renameButton, deleteButton);
        item.append(meta, actions);
        logList.appendChild(item);
    });
}

async function refreshSelectedDataStoreLogs() {
    if (!dataStoreLogList || !activeDataStoreSummary) return;

    const dataStoreId = getSelectedDataStoreId();
    const store = currentDataStores.find(item => item.id === dataStoreId);
    if (!dataStoreId) {
        activeDataStoreSummary.textContent = 'Select a data store to inspect its logs.';
        dataStoreLogList.innerHTML = '';
        return;
    }

    activeDataStoreSummary.textContent = `Active: ${store?.name || dataStoreId} (${dataStoreId})`;
    dataStoreLogList.innerHTML = '<p>Loading logs...</p>';

    try {
        const logs = await fetchJson(`/data-stores/${dataStoreId}/log-summaries`, {
            headers: { 'Accept': 'application/json' }
        });
        if (!logs.length) {
            dataStoreLogList.innerHTML = '<p>No logs are attached to this data store.</p>';
            return;
        }

        dataStoreLogList.innerHTML = '';
        logs.forEach(log => {
            const item = document.createElement('div');
            item.className = 'contained-log-item';
            const meta = document.createElement('div');
            meta.className = 'contained-log-meta';
            meta.innerHTML = `
                <strong>${log.name}</strong>
                <span>${log.logId}${log.createdAt ? ` - Imported: ${log.createdAt}` : ''}</span>
            `;

            const actions = document.createElement('div');
            actions.className = 'log-actions contained-log-actions';

            const deleteButton = document.createElement('button');
            deleteButton.className = 'danger';
            deleteButton.textContent = 'Delete';
            deleteButton.addEventListener('click', () => deleteDataStoreLog(dataStoreId, log));

            actions.appendChild(deleteButton);
            item.append(meta, actions);
            dataStoreLogList.appendChild(item);
        });
    } catch (error) {
        dataStoreLogList.innerHTML = `<p class="error-text">Failed to read store logs: ${error.message}</p>`;
    }
}

async function deleteDataStoreLog(dataStoreId, log) {
    if (!confirm(`Delete log "${log.name}" (${log.logId}) from this data store?`)) {
        return;
    }

    try {
        await apiCall(`/data-stores/${dataStoreId}/logs/${encodeURIComponent(log.logId)}`, { method: 'DELETE' }, uploadOutput);
        showToast(`Log "${log.name}" deleted`, 'success');
        await refreshSelectedDataStoreLogs();
    } catch (error) {
        showToast('Failed to delete log: ' + error.message, 'error');
    }
}

async function refreshLogs() {
    try {
        let dataStores = await apiCall('/data-stores', { headers: { 'Accept': 'application/json' } }, uploadOutput);
        if (!dataStores || dataStores.length === 0) {
            await apiCall('/data-stores', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
                body: JSON.stringify({ name: 'Default' })
            }, uploadOutput);
            dataStores = await apiCall('/data-stores', { headers: { 'Accept': 'application/json' } }, uploadOutput);
        }

        currentDataStores = dataStores || [];
        renderDataStoreOptions();
        renderDataStores();
        await refreshSelectedDataStoreLogs();
    } catch (error) {
        console.error("Failed to refresh data stores", error);
    }
}

async function getLogStatistics(dataStoreId) {
    await apiCall(`/data-stores/${dataStoreId}`, { headers: { 'Accept': 'application/json' } }, uploadOutput);
}

async function deleteLog(dataStoreId) {
    if (confirm(`Are you sure you want to delete data store "${dataStoreId}" and all its logs?`)) {
        try {
            await apiCall(`/data-stores/${dataStoreId}`, { method: 'DELETE' }, uploadOutput);
            showToast(`Data store "${dataStoreId}" deleted`, 'success');
            refreshLogs();
        } catch (e) {
            showToast('Failed to delete data store: ' + e.message, 'error');
        }
    }
}

document.getElementById('refreshLogsButton').addEventListener('click', refreshLogs);
if (createDataStoreButton) {
    createDataStoreButton.addEventListener('click', async () => {
        const name = dataStoreNameInput.value.trim();
        if (!name) {
            showToast('Enter a data store name first.', 'error');
            return;
        }

        setLoading(createDataStoreButton, true, 'Creating...');
        try {
            const created = await apiCall('/data-stores', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
                body: JSON.stringify({ name })
            }, uploadOutput);
            dataStoreNameInput.value = '';
            await refreshLogs();
            if (created?.id) setSelectedDataStore(created.id);
            showToast('Data store created', 'success');
        } catch (error) {
            showToast('Failed to create data store: ' + error.message, 'error');
        } finally {
            setLoading(createDataStoreButton, false);
        }
    });
}

async function renameDataStore(dataStoreId, currentName) {
    const nextName = prompt('New data store name:', currentName);
    if (!nextName || nextName.trim() === currentName) return;

    try {
        await apiCall(`/data-stores/${dataStoreId}`, {
            method: 'PATCH',
            headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
            body: JSON.stringify({ name: nextName.trim() })
        }, uploadOutput);
        showToast('Data store renamed', 'success');
        await refreshLogs();
        setSelectedDataStore(dataStoreId);
    } catch (error) {
        showToast('Failed to rename data store: ' + error.message, 'error');
    }
}

if (activeLogSelect) activeLogSelect.addEventListener('change', () => setSelectedDataStore(activeLogSelect.value));
if (compareLogSelect) compareLogSelect.addEventListener('change', () => setSelectedDataStore(compareLogSelect.value));

function renderRemoteProcessMDataStoreOptions() {
    if (!remoteProcessMDataStoreSelect) return;

    const previousSelection = remoteProcessMDataStoreSelect.value;
    remoteProcessMDataStoreSelect.innerHTML = '';

    if (!currentRemoteProcessMDataStores.length) {
        const option = document.createElement('option');
        option.value = '';
        option.textContent = 'No remote data stores found';
        remoteProcessMDataStoreSelect.appendChild(option);
        return;
    }

    currentRemoteProcessMDataStores.forEach(store => {
        const option = document.createElement('option');
        option.value = store.id;
        option.textContent = `${store.name || store.id} (${store.id})`;
        remoteProcessMDataStoreSelect.appendChild(option);
    });

    const fallbackId = currentRemoteProcessMDataStores[0]?.id || '';
    remoteProcessMDataStoreSelect.value =
        currentRemoteProcessMDataStores.some(store => store.id === previousSelection)
            ? previousSelection
            : fallbackId;
}

async function refreshRemoteProcessMDataStores() {
    if (!remoteProcessMDataStoreSelect) return;

    remoteProcessMDataStoreSelect.innerHTML = '<option value="">Loading remote data stores...</option>';

    try {
        currentRemoteProcessMDataStores = await fetchJson('/query/processm/data-stores', {
            headers: { 'Accept': 'application/json' }
        }) || [];
        renderRemoteProcessMDataStoreOptions();
    } catch (error) {
        currentRemoteProcessMDataStores = [];
        remoteProcessMDataStoreSelect.innerHTML = '<option value="">Failed to load remote data stores</option>';
        showToast('Failed to load remote ProcessM data stores: ' + error.message, 'error');
    }
}

if (refreshRemoteProcessMStoresButton) {
    refreshRemoteProcessMStoresButton.addEventListener('click', refreshRemoteProcessMDataStores);
}

async function loadImportSources() {
    if (!logImportSelect) return;

    try {
        const samples = await fetchJson('/logs/samples', { headers: { 'Accept': 'application/json' } });
        logImportSelect.innerHTML = '<option value="custom">Custom log...</option>';
        (samples || []).forEach(sample => {
            const option = document.createElement('option');
            option.value = sample.resourcePath;
            option.textContent = sample.name;
            option.dataset.sourceType = 'resource';
            logImportSelect.appendChild(option);
        });
        updateImportSourceMode();
    } catch (error) {
        showToast('Failed to load sample log list: ' + error.message, 'error');
    }
}

function updateImportSourceMode() {
    if (!logImportSelect || !customLogFileGroup) return;
    customLogFileGroup.style.display = logImportSelect.value === 'custom' ? '' : 'none';
}

function resourceLogId(resourcePath) {
    return resourcePath
        .split('/')
        .pop()
        .replace(/\.xes\.gz$/i, '')
        .replace(/\.xes$/i, '')
        .replace(/[^a-zA-Z0-9_-]/g, '_');
}

if (logImportSelect) {
    logImportSelect.addEventListener('change', updateImportSourceMode);
}

if (chooseCustomLogButton) {
    chooseCustomLogButton.addEventListener('click', () => {
        document.getElementById('xesFileInput')?.click();
    });
}

document.getElementById('xesFileInput')?.addEventListener('change', (event) => {
    const fileName = event.target.files?.[0]?.name || 'No file selected';
    if (selectedCustomLogName) selectedCustomLogName.textContent = fileName;
});

document.getElementById('uploadButton').addEventListener('click', async () => {
    const fileInput = document.getElementById('xesFileInput');
    const btn = document.getElementById('uploadButton');
    const selectedSource = logImportSelect?.value || 'custom';

    const dataStoreId = activeLogSelect.value;
    if (!dataStoreId) {
        showToast('Please select a data store first.', 'error');
        return;
    }

    try {
        setLoading(btn, true, 'Importing...');

        if (selectedSource === 'custom') {
            if (fileInput.files.length === 0) {
                showToast('Please select a file first.', 'error');
                return;
            }

            const formData = new FormData();
            formData.append('file', fileInput.files[0]);
            await apiCall(`/data-stores/${dataStoreId}/logs`, { method: 'POST', body: formData }, uploadOutput);
        } else {
            const params = new URLSearchParams({
                resourcePath: selectedSource,
                logId: resourceLogId(selectedSource),
                dataStoreId
            });
            await apiCall(`/logs/load-sample?${params}`, { method: 'POST' }, uploadOutput);
        }

        showToast('Log imported successfully!', 'success');
        await refreshLogs();
    } catch (e) {
        showToast('Import failed: ' + e.message, 'error');
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
    const dataStoreId = activeLogSelect.value;
    const query = pqlQueryText.value;

    if (!dataStoreId) {
        showToast('Please select an active data store first.', 'error');
        return;
    }

    if (!query) {
        showToast('Please enter a PQL query first.', 'error');
        return;
    }

    try {
        const originalText = pqlOutput.textContent;
        pqlOutput.textContent = 'Exporting ProcessM ZIP...';
        pqlOutput.style.color = 'var(--text-color)';

        const params = new URLSearchParams({
            query,
            includeTraces: 'true',
            includeEvents: 'true'
        });

        const response = await fetch(`${API_BASE}/data-stores/${dataStoreId}/logs?${params}`, {
            method: 'GET',
            headers: { 'Accept': 'application/zip' }
        });

        if (!response.ok) {
            throw new Error(`Server returned ${response.status}: ${response.statusText}`);
        }

        const blob = await response.blob();
        const contentDisposition = response.headers.get('Content-Disposition');
        let filename = `xes_${Date.now()}.zip`;
        if (contentDisposition) {
            const filenameMatch = contentDisposition.match(/filename="?([^";]+)"?/);
            if (filenameMatch) filename = filenameMatch[1];
        }

        const url = window.URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = filename;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        window.URL.revokeObjectURL(url);

        pqlOutput.textContent = originalText;
        showToast(`ZIP file downloaded successfully: ${filename}`, 'success');
    } catch (error) {
        pqlOutput.style.color = 'var(--red-color)';
        pqlOutput.textContent = `Error exporting ZIP: ${error.message}`;
        showToast(`Failed to export ZIP: ${error.message}`, 'error');
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
    const dataStoreId = activeLogSelect.value;
    const query = pqlQueryText.value;
    if (!dataStoreId) {
        showToast('Please select an active data store first.', 'error');
        return;
    }

    setLoading(pqlButton, true, 'Running...');
    pqlOutput.textContent = 'Executing query through ProcessM-compatible API...';
    pqlOutput.style.color = 'var(--text-color)';

    try {
        const params = new URLSearchParams({
            query,
            includeTraces: 'true',
            includeEvents: 'true'
        });
        const response = await fetch(`${API_BASE}/data-stores/${dataStoreId}/logs?${params}`, {
            method: 'GET',
            headers: { 'Accept': 'application/json' }
        });

        const data = await response.json();
        renderQueryResults({
            success: response.ok,
            query,
            results: data,
            resultCount: Array.isArray(data) ? data.length : 1,
            executionTimeMs: 0
        }, pqlOutput);
        showToast('Query executed', response.ok ? 'success' : 'error');
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
        const dataStoreId = compareLogSelect.value;
        const remoteDataStoreId = remoteProcessMDataStoreSelect?.value || '';
        const selectedStore = currentDataStores.find(store => store.id === dataStoreId);
        const logNameText = selectedStore?.name || dataStoreId;

        const query = compareQueryText.value;

        if (!dataStoreId) {
            showToast('Please select a data store first', 'error');
            return;
        }

        if (!query) {
            showToast('Please enter a query to verify', 'error');
            return;
        }

        if (!remoteDataStoreId) {
            showToast('Please select a remote ProcessM data store first', 'error');
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
                    dataStoreId: dataStoreId,
                    remoteDataStoreId: remoteDataStoreId,
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

function formatComparisonDetails(details) {
    if (!details) return '';
    return details.split('\n').map(line => {
        const escaped = line.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
        // MATCH line (green)
        if (/MATCH/i.test(line) && !/MISMATCH/i.test(line)) {
            return `<div class="diff-match">${escaped}</div>`;
        }
        // MISMATCH line (red)
        if (/MISMATCH/i.test(line)) {
            return `<div class="diff-mismatch">${escaped}</div>`;
        }
        // Difference items (red, with indent)
        if (line.trimStart().startsWith('- ')) {
            return `<div class="diff-item">${escaped}</div>`;
        }
        // "Differences:" header (yellow)
        if (/^Differences:/i.test(line.trim())) {
            return `<div class="diff-header">${escaped}</div>`;
        }
        // Comparison summary
        if (/^Comparison:/i.test(line.trim())) {
            const hasMatch = /MATCH:/i.test(line);
            const cls = hasMatch ? 'diff-match' : 'diff-mismatch';
            return `<div class="${cls}">${escaped}</div>`;
        }
        // Default
        return `<div>${escaped}</div>`;
    }).join('');
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
                        <div style="margin-bottom: 0.5rem;"><strong>Remote Data Store ID:</strong> ${data.remoteDataStoreId || 'Unknown'}</div>
                        <div style="margin-bottom: 0.5rem;"><strong>Adapted Query:</strong> ${data.remoteAdaptedQuery || 'N/A'}</div>
                        <div style="word-break: break-all;"><strong>URL:</strong> ${data.remoteRequestUrl || 'N/A'}</div>
                    </div>
                 </div>
            </div>
            
             <div class="verification-content" style="padding-top: 1rem;">
                <div class="verification-col">
                    <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 0.5rem;">
                        <h4 style="margin: 0;">Local Output</h4>
                        <button onclick="copyVerificationOutput('local')" class="copy-btn" title="Copy to clipboard">📋 Copy</button>
                    </div>
                    <div id="local-results-viewer" style="max-height: 400px; overflow-x: auto; overflow-y: auto; background: #111; padding: 1rem; border-radius: 8px;"></div>
                </div>
                <div class="verification-col">
                    <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 0.5rem;">
                        <h4 style="margin: 0;">Remote Output</h4>
                        <button onclick="copyVerificationOutput('remote')" class="copy-btn" title="Copy to clipboard">📋 Copy</button>
                    </div>
                     <div id="remote-results-viewer" style="max-height: 400px; overflow-x: auto; overflow-y: auto; background: #111; padding: 1rem; border-radius: 8px;"></div>
                </div>
            </div>

            <div class="comparison-details" style="padding: 0 1.5rem 1.5rem; font-size: 0.85rem; font-family: monospace;">${formatComparisonDetails(data.details)}</div>
        </div>
    `;

    verificationResultNew.innerHTML = html;

    // Store verification data globally for copy functionality and snapshot download
    window.currentVerificationData = {
        local: data.localResults,
        remote: data.remoteResults,
        fullData: data  // Store full verification data for snapshot
    };

    // Show download buttons
    const downloadButton = document.getElementById('downloadComparisonButton');
    if (downloadButton) {
        downloadButton.style.display = 'inline-block';
    }
    const lightSnapshotButton = document.getElementById('downloadLightSnapshotButton');
    if (lightSnapshotButton) {
        lightSnapshotButton.style.display = 'inline-block';
    }

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

// Copy verification output to clipboard
function copyVerificationOutput(type) {
    if (!window.currentVerificationData) {
        showToast('No verification data to copy', 'error');
        return;
    }

    const data = type === 'local' ? window.currentVerificationData.local : window.currentVerificationData.remote;
    if (!data) {
        showToast(`No ${type} data available`, 'error');
        return;
    }

    const text = JSON.stringify(data, null, 2);
    navigator.clipboard.writeText(text).then(() => {
        showToast(`${type === 'local' ? 'Local' : 'Remote'} output copied to clipboard!`, 'success');
    }).catch(err => {
        console.error('Failed to copy:', err);
        showToast('Failed to copy to clipboard', 'error');
    });
}

// ProcessM Docker Upload Logic
const uploadToProcessMButton = document.getElementById('uploadToProcessMButton');
const processmLogUpload = document.getElementById('processmLogUpload');
const processmUploadStatus = document.getElementById('processmUploadStatus');

if (uploadToProcessMButton && processmLogUpload) {
    uploadToProcessMButton.addEventListener('click', () => {
        processmLogUpload.click();
    });

    processmLogUpload.addEventListener('change', async (e) => {
        if (!e.target.files.length) return;
        const file = e.target.files[0];
        const logNameText = compareLogSelect.options[compareLogSelect.selectedIndex]?.text || "log.xes";

        uploadToProcessMButton.disabled = true;
        processmUploadStatus.textContent = 'Uploading...';
        processmUploadStatus.style.color = '#aaa';

        const formData = new FormData();
        formData.append('file', file);
        formData.append('logName', logNameText);

        try {
            const response = await fetch('/api/query/processm/upload', {
                method: 'POST',
                body: formData
            });

            const result = await response.json();
            if (response.ok) {
                processmUploadStatus.textContent = 'Upload Success! ' + (result.message || '');
                processmUploadStatus.style.color = '#4caf50';
                showToast('Log uploaded to local ProcessM!', 'success');
                await refreshRemoteProcessMDataStores();
            } else {
                throw new Error(result.error || 'Unknown error');
            }
        } catch (error) {
            console.error('Upload failed:', error);
            processmUploadStatus.textContent = 'Failed: ' + error.message;
            processmUploadStatus.style.color = '#ff6b6b';
            showToast('Failed to upload log: ' + error.message, 'error');
        } finally {
            uploadToProcessMButton.disabled = false;
            // Clear input so change event fires again if same file selected
            processmLogUpload.value = '';
        }
    });
}

// Download comparison snapshot as text file
const downloadComparisonButton = document.getElementById('downloadComparisonButton');
if (downloadComparisonButton) {
    downloadComparisonButton.addEventListener('click', () => {
        if (!window.currentVerificationData || !window.currentVerificationData.fullData) {
            showToast('No verification data to download', 'error');
            return;
        }

        const data = window.currentVerificationData.fullData;
        const timestamp = new Date().toISOString().replace(/[:.]/g, '-');

        // Format the snapshot text
        let snapshot = `ProcessM Verification Comparison Snapshot\n`;
        snapshot += `Generated: ${new Date().toLocaleString()}\n`;
        snapshot += `=`.repeat(80) + `\n\n`;

        snapshot += `VERIFICATION STATUS: ${data.match ? 'MATCH ✓' : 'MISMATCH ✗'}\n\n`;

        snapshot += `QUERY:\n${data.remoteAdaptedQuery || 'N/A'}\n\n`;

        snapshot += `LOCAL EXECUTION:\n`;
        snapshot += `  Success: ${data.localSuccess}\n`;
        snapshot += `  Row Count: ${data.localCount}\n\n`;

        snapshot += `REMOTE PROCESSM:\n`;
        snapshot += `  Success: ${data.remoteSuccess}\n`;
        snapshot += `  Row Count: ${data.remoteCount}\n`;
        snapshot += `  Remote Data Store ID: ${data.remoteDataStoreId || 'Unknown'}\n`;
        snapshot += `  Request URL: ${data.remoteRequestUrl || 'N/A'}\n\n`;

        snapshot += `=`.repeat(80) + `\n`;
        snapshot += `LOCAL OUTPUT (JSON):\n`;
        snapshot += `=`.repeat(80) + `\n`;
        snapshot += JSON.stringify(data.localResults, null, 2) + `\n\n`;

        snapshot += `=`.repeat(80) + `\n`;
        snapshot += `REMOTE OUTPUT (JSON):\n`;
        snapshot += `=`.repeat(80) + `\n`;
        snapshot += JSON.stringify(data.remoteResults, null, 2) + `\n\n`;

        if (data.details) {
            snapshot += `=`.repeat(80) + `\n`;
            snapshot += `DETAILS:\n`;
            snapshot += `=`.repeat(80) + `\n`;
            snapshot += data.details + `\n`;
        }

        // Download as text file
        const blob = new Blob([snapshot], { type: 'text/plain' });
        const url = window.URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `comparison_snapshot_${timestamp}.txt`;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        window.URL.revokeObjectURL(url);

        showToast('Comparison snapshot downloaded!', 'success');
    });
}

// Download light comparison snapshot (diffs only, no full output)
const downloadLightSnapshotButton = document.getElementById('downloadLightSnapshotButton');
if (downloadLightSnapshotButton) {
    downloadLightSnapshotButton.addEventListener('click', () => {
        if (!window.currentVerificationData || !window.currentVerificationData.fullData) {
            showToast('No verification data to download', 'error');
            return;
        }

        const data = window.currentVerificationData.fullData;
        const timestamp = new Date().toISOString().replace(/[:.]/g, '-');

        let snapshot = `ProcessM Verification - Light Snapshot\n`;
        snapshot += `Generated: ${new Date().toLocaleString()}\n`;
        snapshot += `=`.repeat(80) + `\n\n`;

        snapshot += `VERIFICATION STATUS: ${data.match ? 'MATCH' : 'MISMATCH'}\n\n`;

        snapshot += `QUERY:\n${data.remoteAdaptedQuery || 'N/A'}\n\n`;

        snapshot += `LOCAL:  Success=${data.localSuccess}, Count=${data.localCount}\n`;
        snapshot += `REMOTE: Success=${data.remoteSuccess}, Count=${data.remoteCount}\n`;
        snapshot += `Remote Data Store ID: ${data.remoteDataStoreId || 'Unknown'}\n\n`;

        if (data.details) {
            snapshot += `=`.repeat(80) + `\n`;
            snapshot += `COMPARISON DETAILS:\n`;
            snapshot += `=`.repeat(80) + `\n`;
            snapshot += data.details + `\n`;
        }

        // Download as text file
        const blob = new Blob([snapshot], { type: 'text/plain' });
        const url = window.URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `comparison_light_${timestamp}.txt`;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        window.URL.revokeObjectURL(url);

        showToast('Light snapshot downloaded!', 'success');
    });
}

// --- Initial Load ---
document.addEventListener('DOMContentLoaded', () => {
    document.querySelector('label[for="activeLogSelect"]')?.replaceChildren(document.createTextNode('Active Data Store'));
    document.querySelector('label[for="compareLogSelect"]')?.replaceChildren(document.createTextNode('Active Data Store'));
    openTab('logs');
    refreshLogs();
    refreshRemoteProcessMDataStores();
    loadImportSources();
    populateSampleQueries();
});
