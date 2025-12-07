class JsonViewer {
    constructor(options = {}) {
        this.options = {
            theme: options.theme || 'dark', // 'dark' or 'light'
            expanded: options.expanded !== false, // default true
        };
    }

    render(data, container) {
        container.innerHTML = '';
        container.classList.add('json-viewer', this.options.theme);
        const tree = this.createTree(data);
        container.appendChild(tree);
    }

    createTree(data) {
        // Handle null/undefined
        if (data === null) return this.createSimple('null', 'null');
        if (data === undefined) return this.createSimple('undefined', 'undefined');

        // Handle primitives
        const type = typeof data;
        if (type !== 'object') {
            if (type === 'string') return this.createSimple(`"${data}"`, 'string');
            return this.createSimple(data, type);
        }

        // Handle arrays and objects
        const isArray = Array.isArray(data);
        const container = document.createElement('div');
        container.className = 'json-item collapsible';

        // Toggle button/icon
        const toggle = document.createElement('span');
        toggle.className = 'json-toggle';
        toggle.textContent = '▼';
        toggle.onclick = (e) => {
            e.stopPropagation();
            container.classList.toggle('collapsed');
            toggle.textContent = container.classList.contains('collapsed') ? '▶' : '▼';
        };

        // Key/Preview (if part of parent object, handled by parent iteration, 
        // but here we are root or recursing value. 
        // We generally need key-value pairs. 
        // Setup: We assume we are rendering a Value. The Key is rendered by the parent.

        // Let's adjust: createTree returns the value element. 
        // The styling of keys is done in the loop.

        const content = document.createElement('div');
        content.className = 'json-content';

        const openBracket = document.createElement('span');
        openBracket.className = 'json-bracket';
        openBracket.textContent = isArray ? '[' : '{';

        const closeBracket = document.createElement('span');
        closeBracket.className = 'json-bracket';
        closeBracket.textContent = isArray ? ']' : '}';

        const ellipsis = document.createElement('span');
        ellipsis.className = 'json-ellipsis';
        ellipsis.textContent = '...';

        // Header (Toggle + Open Bracket)
        const header = document.createElement('div');
        header.className = 'json-header';
        header.appendChild(toggle);
        header.appendChild(openBracket);
        header.appendChild(ellipsis);

        // Children
        const childrenContainer = document.createElement('div');
        childrenContainer.className = 'json-children';

        const keys = Object.keys(data);
        keys.forEach((key, index) => {
            const childDiv = document.createElement('div');
            childDiv.className = 'json-row';

            // Key (only if not array, or show index if desired, usually array indices are omitted in simple views, but strict view shows them)
            // Let's standard: Objects show keys. Arrays show values directly? 
            // Or Arrays just list values.

            if (!isArray) {
                const keySpan = document.createElement('span');
                keySpan.className = 'json-key';
                keySpan.textContent = `"${key}": `;
                childDiv.appendChild(keySpan);
            }

            const value = data[key];
            const isComplex = typeof value === 'object' && value !== null;

            if (isComplex) {
                // Recurse
                const childTree = this.createTree(value);
                childDiv.appendChild(childTree);
            } else {
                // Simple value
                const simple = this.createTree(value);
                childDiv.appendChild(simple);
            }

            // Comma
            if (index < keys.length - 1) {
                const comma = document.createElement('span');
                comma.className = 'json-comma';
                comma.textContent = ',';
                childDiv.appendChild(comma);
            }

            childrenContainer.appendChild(childDiv);
        });

        container.appendChild(header);
        container.appendChild(childrenContainer);

        // Footer (Close Bracket) - appended to container, outside children
        const footer = document.createElement('div');
        footer.className = 'json-footer';
        footer.appendChild(closeBracket);
        container.appendChild(footer);

        // Header click also toggles
        header.onclick = (e) => {
            // Avoid double toggle if clicked on toggle arrow
            if (e.target !== toggle) {
                toggle.click();
            }
        };

        return container;
    }

    createSimple(value, type) {
        const span = document.createElement('span');
        span.className = `json-value type-${type}`;
        span.textContent = value;
        return span;
    }
}
