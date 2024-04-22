import { LitElement, html, css} from 'lit';
import { root } from 'devui-data';

/**
 * This component shows the Application dependencies
 */
export class QwcAppModelMenu extends LitElement {

    static styles = css`
        :host {
            display: flex;
            flex-direction: column;
            height: 100%;
        }
        .top-bar {
            display: flex;
            align-items: baseline;
            gap: 20px;
            padding-left: 20px;
            justify-content: end;
            padding-right: 20px;
        }
    `;

    static properties = {
        _edgeLength: {type: Number, state: true},
        _root: {state: true},
        _categories: {state: false},
        _colors: {state: false},
        _nodes: {state: true},
        _links: {state: true},
        _showSimpleDescription: {state: false}
    };

    constructor() {
        super();
        console.log(JSON.stringify(root, null, 2));
        this._root = root;
        this._categories =     ['root'   , 'deployment', 'runtime'];
        this._categoriesEnum = ['root'   , 'deployment', 'runtime'];
        this._colors =         ['#ee6666', '#5470c6'   , '#fac858'];
        this._edgeLength = 120;
        this._nodes = null;
        this._links = null;
        this._showSimpleDescription = [];
    }

    connectedCallback() {
        super.connectedCallback();
        this._createNodes();
    }

    _createNodes(){
        let dependencyGraphNodes = this._root.nodes;
        let dependencyGraphLinks = this._root.links;

        this._links = [];
        this._nodes = [];
        for (var l = 0; l < dependencyGraphLinks.length; l++) {
            let link = new Object();
            link.source = dependencyGraphNodes.findIndex(item => item.id === dependencyGraphLinks[l].source);
            link.target = dependencyGraphNodes.findIndex(item => item.id === dependencyGraphLinks[l].target);
            let catindex = this._categoriesEnum.indexOf(dependencyGraphLinks[l].type);

            this._addToNodes(dependencyGraphNodes[link.source],catindex);
            this._addToNodes(dependencyGraphNodes[link.target],catindex);
            this._links.push(link);
        }
    }

    _addToNodes(dependencyGraphNode, catindex){
        let newNode = this._createNode(dependencyGraphNode);
        let index = this._nodes.findIndex(item => item.id === newNode.id);
        if (index < 0 ) {
            if(dependencyGraphNode.id === this.id){
                newNode.category = 0; // Root
            }else {
                newNode.category = catindex;
            }
            this._nodes.push(newNode);
        }
    }

    _createNode(node){
        let nodeObject = new Object();
        if(this._showSimpleDescription.length>0){
            nodeObject.name = node.name;
        }else{
            nodeObject.name = node.description;
        }

        nodeObject.value = node.value;
        nodeObject.id = node.id;
        nodeObject.description = node.description;
        return nodeObject;
    }

    render() {
        return html`${this._renderTopBar()}
                        <echarts-force-graph width="400px" height="400px"
                            edgeLength=${this._edgeLength}
                            categories="${JSON.stringify(this._categories)}"
                            colors="${JSON.stringify(this._colors)}"
                            nodes="${JSON.stringify(this._nodes)}"
                            links="${JSON.stringify(this._links)}">
                        </echarts-force-graph>`;
    }

    _renderTopBar(){
            return html`
                    <div class="top-bar">
                        <div>
                            ${this._renderCheckbox()}

                            <vaadin-button theme="icon" aria-label="Zoom in" @click=${this._zoomIn}>
                                <vaadin-icon icon="font-awesome-solid:magnifying-glass-plus"></vaadin-icon>
                            </vaadin-button>
                            <vaadin-button theme="icon" aria-label="Zoom out" @click=${this._zoomOut}>
                                <vaadin-icon icon="font-awesome-solid:magnifying-glass-minus"></vaadin-icon>
                            </vaadin-button>
                        </div>
                    </div>`;
    }

    _renderCheckbox(){
        return html`<vaadin-checkbox-group
                        .value="${this._showSimpleDescription}"
                        @value-changed="${(event) => {
                            this._showSimpleDescription = event.detail.value;
                            this._createNodes();
                        }}">
                        <vaadin-checkbox value="0" label="Simple description"></vaadin-checkbox>
                    </vaadin-checkbox-group>`;
    }

    _zoomIn(){
        if(this._edgeLength>10){
            this._edgeLength = this._edgeLength - 10;
        }else{
            this._edgeLength = 10;
        }
    }

    _zoomOut(){
        this._edgeLength = this._edgeLength + 10;
    }

}
customElements.define('qwc-app-model-menu', QwcAppModelMenu);