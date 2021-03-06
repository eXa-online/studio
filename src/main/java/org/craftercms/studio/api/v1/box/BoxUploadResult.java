package org.craftercms.studio.api.v1.box;

/**
 * Holds the information of file uploaded to Box
 *
 * @author joseross
 */
public class BoxUploadResult {

    /**
     * The ID generated by Box for the file
     */
    protected String id;

    /**
     * The name of the file uploaded
     */
    protected String name;

    public String getId() {
        return id;
    }

    public void setId(final String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(final String name) {
        this.name = name;
    }

    @Override
    public String toString() {
        return "BoxUploadResult{" + "id='" + id + '\'' + ", name='" + name + '\'' + '}';
    }

}
