# Preview
`/vialogium preview`  
Alias: `pv`  
Permission: `vialogium.commands.preview`

---

### `/vialogium preview rollback <params>`
This command will find **all** actions that match the [parameters](../parameters.md) that ***haven't*** already been rolled back.
It will then send a preview of what the world would look like with all the selected actions undone.
This is much safer than using the raw rollback command and is highly recommended over it.

**Supported Previews**

- Block

### `/vialogium preview restore <params>`
Similar to `/vialogium preview rollback`, this command will find **all** actions that match the [parameters](../parameters.md) that ***have*** already been rolled back.
It will then send a preview of what the world would look like with all the selected actions re-applied.
This is much safer than using the raw preview command and is highly recommended over it.

**Supported Previews**

- Block

### `/vialogium preview apply`
This will apply your current preview to the world if you have one.

### `/vialogium preview cancel`
This will cancel your current preview if you have one.
