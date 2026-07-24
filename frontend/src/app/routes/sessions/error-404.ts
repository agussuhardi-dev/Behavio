import { Component } from '@angular/core';
import { ErrorCode } from '@shared/components/error-code/error-code';
import { TranslatePipe } from '@ngx-translate/core';

@Component({
  selector: 'app-error-404',
  template: `
    <error-code
      code="404"
      [title]="'errpage.e404_title' | translate"
      [message]="'errpage.e404_msg' | translate"
    />
  `,
  imports: [ErrorCode, TranslatePipe],
})
export class Error404 {}
