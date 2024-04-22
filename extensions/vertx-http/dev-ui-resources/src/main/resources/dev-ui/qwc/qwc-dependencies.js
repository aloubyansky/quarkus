import { LitElement, html, css} from 'lit';
import { root } from 'devui-data';
import { groupIds } from 'devui-data';
import { filteredGroupIds } from 'devui-data';
import '@vaadin/button';
import '@vaadin/icon';
import '@vaadin/checkbox';
import '@vaadin/checkbox-group';

/**
 * This component shows the Application dependencies
 */
export class QwcDependencies extends LitElement {

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
        .middle {
            display: flex;
            width:100%;
            height: 100%;
        }
        .filter {
            overflow: scroll;
        }
    
    `;

    static properties = {
        _edgeLength: {type: Number, state: true},
        _root: {state: true},
        _categories: {state: false},
        _colors: {state: false},
        _nodes: {state: true},
        _links: {state: true},
        _showSimpleDescription: {state: false},
        _showDirectOnly: {state: false},
        _groupIds: {state: false},
        _selectedGroupIds: {state: false}
    };

    constructor() {
        super();
        this._root = root;
        this._categories =     ['root'   , 'deployment', 'runtime'];
        this._categoriesEnum = ['root'   , 'deployment', 'runtime'];
        this._colors =         ['#ee6666', '#5470c6'   , '#91cc75'];
        this._edgeLength = 120;
        this._nodes = null;
        this._links = null;
        this._showSimpleDescription = [];
        this._showDirectOnly = [];
        this._groupIds = groupIds;
        this._selectedGroupIds = filteredGroupIds;
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
            let linkSpec = dependencyGraphLinks[l];
            let sourceNode = dependencyGraphNodes.find(item => item.id === linkSpec.source);
            let targetNode = dependencyGraphNodes.find(item => item.id === linkSpec.target);
            let catindex = this._categoriesEnum.indexOf(linkSpec.type);
            
            if(this._showDirectOnly.length==0 || (this._showDirectOnly.length>0 && this._isDirect(linkSpec))){
                const filterOutSet = new Set(this._selectedGroupIds);
                
                let targetGroupId = linkSpec.target.split(':')[0];
                
                if (!filterOutSet.has(targetGroupId)) {
                
                    this._addToNodes(sourceNode, catindex);
                    this._addToNodes(targetNode, catindex);

                    let link = new Object();
                    link.target = this._nodes.findIndex(item => item.id === sourceNode.id);
                    link.source = this._nodes.findIndex(item => item.id === targetNode.id);
                    this._links.push(link);
                }
            }
        }
    }

    _addToNodes(dependencyGraphNode, catindex){
        let addedNode = this._nodes.find(item => item.id === dependencyGraphNode.id);
        if (!addedNode) {
            let newNode = this._createNode(dependencyGraphNode);
            if(this._isRoot(dependencyGraphNode)){
                newNode.category = 0; // Root
            }else {
                newNode.category = catindex;
            }
            this._nodes.push(newNode);
        } else if (addedNode.category > 0 && addedNode.category < catindex) {
           addedNode.category = catindex;
        }
    }

    _isDirect(dependencyGraphLink){
        return dependencyGraphLink.direct || dependencyGraphLink.source === this._root.rootId;
    }

    _isRoot(dependencyGraphNode){
        return dependencyGraphNode.id === this._root.rootId;
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
                        <div class="middle">    
                            ${this._renderFilter()}
                            <echarts-force-graph width="400px" height="400px"
                                edgeLength=${this._edgeLength}
                                categories="${JSON.stringify(this._categories)}"
                                colors="${JSON.stringify(this._colors)}"
                                nodes="${JSON.stringify(this._nodes)}"
                                links="${JSON.stringify(this._links)}">
                            </echarts-force-graph>
                        </div>`;
        
    }

    _renderTopBar(){
            return html`
                    <div class="top-bar">
                        <div>
                            ${this._renderDirectOnlyCheckbox()}
                            ${this._renderSimpleDescriptionCheckbox()}
                            
                            <vaadin-button theme="icon" aria-label="Zoom in" @click=${this._zoomIn}>
                                <vaadin-icon icon="font-awesome-solid:magnifying-glass-plus"></vaadin-icon>
                            </vaadin-button>
                            <vaadin-button theme="icon" aria-label="Zoom out" @click=${this._zoomOut}>
                                <vaadin-icon icon="font-awesome-solid:magnifying-glass-minus"></vaadin-icon>
                            </vaadin-button>
                        </div>
                    </div>`;
    }

    _renderFilter(){
        return html`<div class="filter">
            <vaadin-checkbox-group
                label="Filter out group id:"
                    .value="${this._selectedGroupIds}"
                    @value-changed="${(event) => {
                        this._selectedGroupIds = event.detail.value;
                        this._createNodes();
                    }}"
                    theme="vertical">
                ${this._groupIds.map((groupId) =>
                    html`<vaadin-checkbox value="${groupId}" label="${groupId}"></vaadin-checkbox>`
                )}
              </vaadin-checkbox-group>
            </div>
          `;
    }

    _renderSimpleDescriptionCheckbox(){
        return html`<vaadin-checkbox-group
                        .value="${this._showSimpleDescription}"
                        @value-changed="${(event) => {
                            this._showSimpleDescription = event.detail.value;
                            this._createNodes();
                        }}">
                        <vaadin-checkbox value="0" label="Simple description"></vaadin-checkbox>
                    </vaadin-checkbox-group>`;
    }

    _renderDirectOnlyCheckbox(){
        return html`<vaadin-checkbox-group
                        .value="${this._showDirectOnly}"
                        @value-changed="${(event) => {
                            this._showDirectOnly = event.detail.value;
                            this._createNodes();
                        }}">
                        <vaadin-checkbox value="0" label="Direct Only"></vaadin-checkbox>
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
customElements.define('qwc-dependencies', QwcDependencies);